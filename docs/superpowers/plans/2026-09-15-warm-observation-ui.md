# Warm Observation UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 보호자가 색으로 건강 상태를 오해하지 않으면서 기록·진료 준비·공유를 쉽게 사용하는 '따뜻한 관찰 노트' UI를 완성한다.

**Architecture:** 기존 React 화면과 React Query 데이터 흐름을 유지하고, 역할 기반 디자인 토큰과 작은 공통 UI를 도입한다. 상태 판정·API payload는 유지하면서 표시 계층의 로딩·오류·선택·포커스를 명확히 한다. 최신 원격의 QA 수정과 로컬 QA 보고서·승인 명세를 함께 보존한 작업 브랜치에서 진행한다.

**Tech Stack:** React 19, TypeScript, Tailwind CSS 4, React Router 7, TanStack Query 5, Vitest, Testing Library, MSW, Playwright. Node 22.22.2와 기존 package-lock.json 사용.

**Spec:** `docs/superpowers/specs/2026-09-15-warm-observation-ui-design.md` — 사용자 상세 설계 승인: 2026-09-15, '구현계획 진행'.

## Global Constraints

- 색은 브랜드, 화면의 구획, 사용자 조작과 선택을 표현한다. 환자의 호전·악화·위험도를 표현하지 않는다.
- 모든 도움 수준과 관찰 변화에는 같은 중립적 표현 체계를 쓴다.
- 상태 판정 문구, 카탈로그 라벨, 질문과 관찰 근거는 기존 서버 결과를 그대로 표시한다.
- 다음 기록의 정확한 날짜는 현재 Me 응답에 없으므로 임의 계산하지 않는다.
- 일반 글자는 배경 대비 4.5:1 이상, 컨트롤 경계와 포커스는 인접 면 대비 3:1 이상으로 검증한다.
- 글꼴: 현재 한국어 시스템 글꼴을 유지한다. 본문 18px, 보조 16px, 제목 26~32px.
- 주 버튼: 높이 최소 56px. 기타 조작도 터치 영역 최소 48px.
- 움직임: 선택·버튼 피드백 120~180ms. 이동량은 작게 하고 reduced-motion 설정에서는 제거한다.
- 동일 출처의 로컬 아이콘과 시스템 글꼴을 사용한다. 별도 디자인 프레임워크나 외부 이미지 서비스는 추가하지 않는다.
- 치료사 화면의 토큰 처리, 제3자 리소스 차단, API와 요약 비캐시 규칙을 유지한다.
- 대상은 랜딩, 홈, 온보딩, 주간 기록, 준비 카드, 전체 기록, 설정, 이어받기, 치료사 보기, 오류·로딩 상태다.
- 탭 바·계정·건강 지표·운동 추천 등 신규 제품 기능은 이번 개선에 포함하지 않는다.
- 기존 문구 화이트리스트는 제거하거나 느슨한 금지어 검사로 바꾸지 않는다. 승인된 조작·안내 문구를 명시적으로 갱신하고, 서버 문장 원문 검증은 유지한다.
- 임의 건강 데이터로 배포 서비스를 수정하지 않는다. 테스트는 로컬 MSW/브라우저 fixture와 기존 로컬 통합 환경에서 수행한다.

## 실행 기반과 작업 파일

계획 수립 기준: 원격 main `e6f7692`, 로컬 QA `e7a35a7`, 승인 명세 `e9563a7`.
현재 로컬의 `.gitignore` 미커밋 변경은 사용자 변경이다. 기존 보고서를 삭제하거나 강제 덮어쓰지 않는다.
작업 시작 시 using-git-worktrees 절차로 `codex/warm-observation-ui` 작업 공간을 준비한다.
로컬 계획 커밋까지 포함한 기준에서 원격 main을 일반 merge해 양쪽 이력을 보존한다.
이미 다른 수정이 추가됐다면 먼저 차이를 읽고 관련 변경을 계획에 반영한다. 배포 여부는 커밋 병합만으로 단정하지 않는다.

| 파일 | 역할 / 작업 |
| --- | --- |
| `frontend/src/styles.css` | 토큰, 노트 면, 버튼·입력·포커스, 반응형과 인쇄 |
| `frontend/src/ui/PageHeader.tsx` (신규) | 제목, 홈/설정 동선, 진입 포커스 |
| `frontend/src/ui/Icon.tsx` (신규) | 일정·노트·질문·공유의 로컬 선 아이콘 |
| `frontend/src/ui/AsyncState.tsx` (신규) | 로딩·오류 상태와 재시도 |
| `frontend/src/ui/ScrollRegion.tsx` (신규) | 이름 있는 키보드 접근 가능 가로 기록 영역 |
| `frontend/src/ui/ObservationValue.tsx` (신규) | 값과 출처 표식의 일관된 표시 |
| `frontend/src/ui/CopyButton.tsx` (신규) | 코드·주소 복사와 실패 안내 |
| `frontend/src/ui/Button.tsx`, `Choice.tsx`, `Notice.tsx`, `Collapse.tsx`, `Screen.tsx` | 기존 인터페이스를 호환 확장 |
| `frontend/src/lib/flowProgress.ts` (신규) | 온보딩 진행 구간 표시. 기존 저장 단계는 유지 |
| `frontend/src/screens/Landing.tsx` (신규) | 홈의 첫 방문 분기에서 사용하는 서비스 소개 |
| `frontend/src/screens/Home.tsx` | 데이터 상태별 홈 정보 순서 |
| `frontend/src/screens/Onboarding.tsx`, `Record.tsx` | 질문 단계·포커스·하단 행동 |
| `frontend/src/screens/PrepCard.tsx`, `Trajectory.tsx`, `Therapist.tsx` | 질문과 읽기용 기록 표현 |
| `frontend/src/screens/Settings.tsx`, `Recover.tsx`, `Demo.tsx`, `NotFound.tsx` | 보조 흐름과 복구 동선 |
| `frontend/src/lib/catalog.tsx` | 앱 진입 로딩·연결 실패에 공통 UI 적용 |
| 각 수정 화면의 기존 `*.test.tsx` | 원문·payload·분기 회귀 검사 유지 및 동작 검사 추가 |
| `frontend/e2e/warm-observation.spec.ts` (신규) | 브라우저에서 레이아웃·포커스·마지막 주차 접근 검증 |
| `docs/superpowers/specs/2026-09-06-frontend-design.md` | 새 시각 규칙 개정 링크 |

의존 순서: Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7.
개별 작업 뒤에는 관련 테스트와 화면을 확인한다. 전체 테스트는 기준선과 최종 단계에서 실행하고, 중간 실패가 새 회귀를 시사하면 범위를 넓힌다.

---

### Task 1: 최신 수정 기반에서 공통 디자인과 상태 UI 만들기

**Files:** 위 표의 styles.css, PageHeader.tsx, Icon.tsx, AsyncState.tsx, Button.tsx, Choice.tsx, Notice.tsx, Collapse.tsx, lib/catalog.tsx 및 기존 설계 문서.
**Tests:** Create `frontend/src/ui/Choice.test.tsx`, `AsyncState.test.tsx`, `PageHeader.test.tsx`, `Collapse.test.tsx`; 기존 catalog.test.ts 유지.

**Interfaces:**
- `PageHeader({ title, backTo?, settings?, focusKey? }: { title: string; backTo?: string; settings?: boolean; focusKey?: string | number })`
- `AsyncState({ kind, message, onRetry? }: { kind: 'loading' | 'error'; message: string; onRetry?: () => void })`
- `Icon({ name }: { name: 'calendar' | 'notebook' | 'question' | 'share' })` — 항상 글자와 함께 사용하는 장식 아이콘.
- 기존 Button·Choice·Notice·Collapse props는 유지한다. Notice에는 `role?: 'status' | 'alert'`만 선택적으로 추가한다.
- `PageHeader`는 h1을 렌더한다. focusKey가 있을 때 그 값이 바뀔 때만 h1에 포커스한다. query 재렌더로 포커스를 옮기지 않는다.

- [ ] 작업 공간에서 remote/main 통합 상태, 기존 QA 보고서와 spec, 사용자 변경 보존을 확인한다. Node 22.22.2를 선택하고 다음 기준선 명령을 실행한다.

```bash
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
```

기존 실패는 이름과 출력으로 기록한다. 새 변경의 성공으로 덮어쓰지 않는다. 이전 렌더를 랜딩·홈·입력·준비 카드별로 375px와 1280px에서 저장한다.

- [ ] 색 없이 선택 상태가 보이는 테스트를 추가하고 실패를 확인한다.

```tsx
import { expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Choice } from './Choice';

it('선택을 표식과 aria 상태로 표시하고 선택만으로 이동하지 않는다', async () => {
  const onSelect = vi.fn();
  const { rerender } = render(<Choice label="혼자 하심" selected={false} onSelect={onSelect} />);
  const choice = screen.getByRole('button', { name: '혼자 하심' });
  await userEvent.setup().click(choice);
  expect(onSelect).toHaveBeenCalledTimes(1);
  rerender(<Choice label="혼자 하심" selected onSelect={onSelect} />);
  expect(choice).toHaveAttribute('aria-pressed', 'true');
  expect(within(choice).getByText('✓')).toHaveAttribute('aria-hidden', 'true');
});
```

Run: `npm --prefix frontend test -- src/ui/Choice.test.tsx`.
Expected before implementation: 체크 표식이 없어 실패. 기존 접근성 이름과 버튼 의미는 유지한다.

- [ ] 토큰을 명세 값으로 교체하고 종이 면·주 버튼·선택 표시의 공통 클래스를 구현한다.

```css
@theme {
  --color-ink: #243247;
  --color-ink-soft: #586476;
  --color-ink-faint: #586476;
  --color-line: #DDD7CB;
  --color-control: #7C8794;
  --color-canvas: #F7F5F0;
  --color-paper: #FFFFFF;
  --color-paper-soft: #EEE9DF;
  --color-accent: #365D83;
  --color-accent-soft: #E8EFF5;
  --text-small: 16px;
  --text-body: 18px;
  --text-title: 28px;
}
body { background: var(--color-canvas); }
.note-surface {
  background: var(--color-paper);
  border: 1px solid var(--color-line);
  border-radius: 20px;
  padding: 24px;
  box-shadow: 0 3px 12px rgb(36 50 71 / 4%);
}
:where(button, a, input, textarea, [tabindex]):focus-visible {
  outline: 3px solid var(--color-accent);
  outline-offset: 3px;
}
.choice { border: 2px solid var(--color-control); transition: background-color 150ms; }
.choice[aria-pressed="true"] { border-color: var(--color-accent); }
@media (max-width: 359px) { .note-surface { padding: 16px; } }
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
}
```

기존 `@theme`에 병합하며 중복 정의하지 않는다. 입력 경계는 `border-control`, 읽기용 경계는 `border-line`을 사용한다.
선택 전에도 표식 자리의 폭은 유지한다. 노트 전체에 hover·pointer를 주지 않는다.

일정·노트·질문·공유는 다음 로컬 SVG로 통일한다. 사용 위치의 링크·제목에 실제 글자 라벨을 둔다.

```tsx
const paths = {
  calendar: 'M5 5h14v15H5z M5 10h14 M8 3v4 M16 3v4',
  notebook: 'M6 3h13v18H6z M3 7h5 M3 12h5 M3 17h5 M11 8h5 M11 12h5',
  question: 'M5 4h14v12H9l-4 4z M10 8a2 2 0 0 1 4 0c0 2-2 1-2 3 M12 13v1',
  share: 'M12 3v12 M8 7l4-4 4 4 M5 12v8h14v-8',
} as const;
export function Icon({ name }: { name: keyof typeof paths }) {
  return <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    <path d={paths[name]} />
  </svg>;
}
```

- [ ] PageHeader와 AsyncState를 구현한다. 로딩은 `role="status"`, 오류는 `role="alert"`; 재시도 버튼은 오류 상태에만 보인다.

```tsx
export function AsyncState({ kind, message, onRetry }: {
  kind: 'loading' | 'error'; message: string; onRetry?: () => void;
}) {
  return <div className="note-surface">
    <p role={kind === 'error' ? 'alert' : 'status'}>{message}</p>
    {kind === 'error' && onRetry ? <Button variant="plain" onClick={onRetry}>다시 시도하기</Button> : null}
  </div>;
}
```

AsyncState.tsx는 `Button`을 `./Button`에서 import한다. 테스트는 onRetry 호출, 로딩에 retry 부재를 확인한다.
PageHeader 테스트는 최초/키 변경에 제목 포커스, 같은 키 재렌더에서 입력 포커스 유지 여부를 확인한다.
Collapse는 useId로 버튼 aria-controls와 열린 패널 id를 연결하고 접힘/열림을 테스트한다.

- [ ] CatalogProvider의 useQuery에서 refetch를 받아 로딩·오류에 서비스 이름과 AsyncState를 적용한다.
catalogProvider는 App 안에서만 사용하므로 치료사 별도 라우트에 종속성을 추가하지 않는다.
기존 설계 문서에 승인 명세 링크를 추가하고 색·노트 면·빈 상태 규칙 개정을 명시한다.
- [ ] Run: `npm --prefix frontend test -- src/ui src/lib/catalog.test.ts` 및 `npm --prefix frontend run typecheck`.
브라우저에서 320px·375px 선택지와 포커스 경계를 확인한 뒤 관련 파일만 커밋한다.
Commit: `style: establish warm observation design system`.

### Task 2: 랜딩과 상태별 홈의 정보 순서 개선

**Files:** Create `frontend/src/screens/Landing.tsx`; Modify `Home.tsx`, `styles.css`; Test `Home.test.tsx`, `lib/home.test.ts`.
**Interfaces:** Landing은 props 없이 APP_NAME과 기존 경로를 사용한다. Home은 기존 `catalog: Catalog` prop을 유지한다. Home의 query 객체를 유지해 pending/error/data를 구분하고 기존 homeState에는 성공 응답만 전달한다.

- [ ] Home.test.tsx의 기존 `serve`, `renderHome`, `silent`와 fixtures `me`를 재사용해 다음 회귀 테스트를 추가한다.

```tsx
it('경과 요청 실패를 변화 없음이라고 말하지 않는다', async () => {
  setToken('t');
  serve({ me: me({ recordedThisWeek: true }) });
  server.use(http.get(`${BASE}/me/progress`, () => HttpResponse.json({}, { status: 503 })));
  renderHome();
  expect(await screen.findByRole('alert')).toHaveTextContent('관찰 내용을 불러오지 못했습니다.');
  expect(screen.queryByText('이번 기간에는 바뀐 항목이 없습니다.')).not.toBeInTheDocument();
});
```

Run: `npm --prefix frontend test -- src/screens/Home.test.tsx`. Expected before: 실패 안내가 없어 실패.
추가로 progress 응답을 지연시켜 완료 전 '변화 없음' 부재를 확인한다.

- [ ] 랜딩은 서비스 소개와 예시 노트의 두 영역으로 만든다. 다음 고정 문구를 화이트리스트에 명시한다.

```tsx
<h1>집에서의 관찰을, 다음 진료의 질문으로</h1>
<p>집에서 보신 것을 남겨두시면, 다음에 병원 가실 때 여쭤볼 것을 만들어 드립니다.</p>
<Link className="btn" to="/onboarding">관찰 기록 시작하기</Link>
<Link to="/demo">먼저 둘러보기</Link>
<Link to="/recover">기존 기록 이어받기</Link>
<ol aria-label="이용 방법">
  <li>관찰 남기기</li><li>질문 준비하기</li><li>진료실에서 함께 보기</li>
</ol>
<section className="note-surface" aria-label="관찰 노트 예시">
  <p>예시</p><h2>집에서 본 장면을 남겨요</h2>
  <p>관찰한 내용을 모아 진료실에서 여쭤볼 질문을 준비합니다.</p>
</section>
```

APP_NAME은 상단에 별도 표시한다. 넓은 화면은 최대 1040px 두 열, 768px 미만은 한 열로 놓는다.
하나의 주 버튼만 사용하며 예시에는 데모 환자의 건강 변화 문구도 삽입하지 않는다.

- [ ] Home은 다음 순서로 query 상태를 처리한다. hooks는 모든 조건부 return보다 위에 둔다.

```tsx
const hasToken = getToken() !== null;
const meQ = useMe(hasToken);
const recorded = meQ.data?.recordedThisWeek === true;
const progressQ = useProgress(recorded);
const visitSoon = isVisitSoon(meQ.data?.nextVisitDate ?? null, meQ.data?.today ?? '');
const prepQ = usePrepCard(visitSoon);
```

토큰 없음은 Landing. me pending/error는 제목과 AsyncState. 기록 후 progress pending/error는 완료 정보와
해당 영역의 AsyncState를 표시한다. 성공 데이터가 있을 때만 homeState의 SILENT/CHANGES를 사용한다.
준비 카드 query 실패는 날짜와 기록을 지우지 않고 준비 영역에서 재시도한다. 실패 메시지는 고정 문구로 화이트리스트에 추가한다.

- [ ] 최상단 브랜드 헤더와 설정 링크, 핵심 노트, 진료 정보, 관찰 노트, 보조 동선을 배치한다.
미기록이면 주 버튼은 기존 '3분 기록하기' 또는 카탈로그 개수+'가지 확인하기'. 기록 완료·가까운 진료면
'진료 준비 카드 보기'. 관찰 변화는 한 노트 안의 구분선 있는 목록이며 서버 from/to/message를 유지한다.
기록 완료 문구는 '이번 주 기록을 남겼습니다'와 체크 아이콘으로 정리하고 현재 주차를 별도 표시한다.
API가 제공하지 않는 다음 기록 날짜는 표시하지 않는다. 기존 densityPhrase를 그대로 사용한다.
- [ ] 기존 테스트의 승인된 정적 문구·버튼 이름만 갱신한다. 원문, 전환만 있는 상태, 0개 질문, 동적 카탈로그 개수 검사를 보존한다.
Run: `npm --prefix frontend test -- src/screens/Home.test.tsx src/lib/home.test.ts`.
375px/1280px에서 첫 방문·미기록·기록 완료·가까운 진료 네 상태를 렌더링해 캡처한다.
Commit: `feat: redesign landing and guardian home hierarchy`.

### Task 3: 입력 흐름의 진행 표시·선택·포커스 개선

**Files:** Create `lib/flowProgress.ts`; Modify `ui/Screen.tsx`, `screens/Onboarding.tsx`, `screens/Record.tsx`, `styles.css`.
**Tests:** Create `lib/flowProgress.test.ts`, `ui/Screen.test.tsx`; Modify `screens/Onboarding.test.tsx`, `OnboardingBaseline.test.tsx`, `Record.test.tsx`.
**Interfaces:**
- `onboardingProgress(step: number, itemCount: number): { label: string; current?: number; total?: number }`.
- Screen의 기존 props에 `stageLabel?: string`, `focusKey?: string | number`를 추가한다. step/total은 진행 수치, focusKey는 화면 변경만 식별한다.
- Screen은 제목을 새로 중복 생성하지 않고 자식의 실제 주 질문 제목 `[data-step-title]`로 포커스를 옮긴다. 각 단계의 주 질문은 h1, 하위 축 제목은 h2로 정돈한다.

- [ ] 진행 문구의 구간 경계를 검증하는 테스트를 추가한다.

```ts
import { expect, it } from 'vitest';
import { onboardingProgress } from './flowProgress';
it('생활 관찰의 위치와 마무리 구간을 구별한다', () => {
  expect(onboardingProgress(7, 8)).toEqual({ label: '생활 관찰', current: 1, total: 8 });
  expect(onboardingProgress(14, 8)).toEqual({ label: '생활 관찰', current: 8, total: 8 });
  expect(onboardingProgress(15, 8)).toEqual({ label: '마무리' });
  expect(onboardingProgress(7, 5)).toEqual({ label: '생활 관찰', current: 1, total: 5 });
});
```

Run: `npm --prefix frontend test -- src/lib/flowProgress.test.ts`. Expected: 새 모듈이 없어 실패.
- [ ] existing FIRST_BASELINE_STEP/RECOVERY_STEP을 import해 다음 표시 함수를 구현한다. 저장 단계 상수나 persisted draft 형식은 변경하지 않는다.

```ts
import { FIRST_BASELINE_STEP, RECOVERY_STEP } from './onboarding';
export function onboardingProgress(step: number, itemCount: number): {
  label: string; current?: number; total?: number;
} {
  if (step === 0) return { label: '시작 안내' };
  if (step < FIRST_BASELINE_STEP) return { label: '기본 정보' };
  if (step < RECOVERY_STEP && step - FIRST_BASELINE_STEP < itemCount) {
    return { label: '생활 관찰', current: step - FIRST_BASELINE_STEP + 1, total: itemCount };
  }
  return { label: '마무리' };
}
```

이번 MVP 저장 모델은 8개 항목으로 고정되어 있다. 함수가 다른 항목 수를 표시할 수 있다는 테스트는
온보딩 저장이 임의 카탈로그 길이를 지원한다는 뜻이 아니다. 실제 표시 수는 기존 itemForStep이 접근 가능한 항목 수로 제한한다.

- [ ] Screen에 `role="progressbar"`와 aria-valuemin/max/now/text, 가시적인 구간·단계를 제공한다.
선택 하위 질문이 열려도 focusKey는 유지한다. 다음/뒤로로 단계가 바뀔 때만 다음 효과를 실행한다.

```tsx
const contentRef = useRef<HTMLDivElement>(null);
useEffect(() => {
  if (focusKey === undefined) return;
  contentRef.current?.querySelector<HTMLElement>('[data-step-title]')?.focus();
}, [focusKey]);
```

useRef/useEffect를 React에서 import한다. 제목은 `tabIndex={-1}`을 갖는다. Screen.test는 키 변경으로 제목 포커스,
같은 키에서 선택/입력 포커스 유지, progressbar 값과 가시적인 단계 문구를 검사한다.
- [ ] 하단을 `.flow-footer`로 표시하고 아래 내용을 적용한다.

```css
.flow-shell { min-height: 100dvh; display: flex; flex-direction: column; }
.flow-content { flex: 1; padding-bottom: 24px; }
.flow-footer {
  position: sticky; bottom: 0;
  padding: 16px 0 max(16px, env(safe-area-inset-bottom));
  background: var(--color-canvas); border-top: 1px solid var(--color-line);
}
.flow-content :where(input, textarea, button) { scroll-margin-bottom: 140px; }
@media (max-height: 480px) { .flow-footer { position: static; } }
```

주간 Record의 현재 `flow.length`와 `s.index + 1`로 표시한다. noChange, fullRecheck, signalsEnabled 분기를 유지한다.
Onboarding 완료/복구/알림에도 실제 단계 제목과 stageLabel을 적용한다. 저장·연결 오류는 입력 근처에서 명확히 표시한다.
- [ ] Run: `npm --prefix frontend test -- src/ui/Screen.test.tsx src/lib/flowProgress.test.ts src/screens/Onboarding.test.tsx src/screens/OnboardingBaseline.test.tsx src/screens/Record.test.tsx`.
초안 새로고침 복원, 뒤로, 조건부 손 질문, 전체 재확인 이전 값, 중복 저장, 주차 변경 테스트를 유지한다.
모바일 키보드를 연 상태 또는 작은 높이로 마지막 항목·오류·버튼이 가려지지 않는지 실제 확인한다.
Commit: `feat: clarify observation flow progress and focus`.

### Task 4: 진료 질문 노트와 저장 상태 개선

**Files:** Modify `screens/PrepCard.tsx`, `PrepCard.test.tsx`, `ui/Collapse.tsx`, `styles.css`.
**Interfaces:** 기존 usePrepCard/useSaveExtra, PrepQuestion.sentence/evidence 그대로 사용한다. 질문 하나의 부모는 `article aria-labelledby`로 이름을 갖는다.

- [ ] 기존 full fixture와 renderIt를 사용하고 within을 import해 질문·근거 소속을 테스트한다.

```tsx
it('질문 안에서 해당 근거를 펼친다', async () => {
  renderIt(full);
  const article = await screen.findByRole('article', { name: full.questions[0]!.sentence });
  await userEvent.setup().click(within(article).getByRole('button', { name: '이 질문의 관찰 근거' }));
  expect(within(article).getByText('손 잡아드림')).toBeInTheDocument();
});
```

Run: `npm --prefix frontend test -- src/screens/PrepCard.test.tsx`. Expected before: 이름 있는 article이 없어 실패.
- [ ] PageHeader 아래 ol/li 구조를 유지하며 각각 다음과 같이 묶는다.

```tsx
<article className="note-surface" aria-labelledby={`question-${q.rank}`}>
  <p className="text-small text-ink-soft">질문 {q.rank}</p>
  <h2 id={`question-${q.rank}`} className="text-[22px] font-semibold">{q.sentence}</h2>
  <Collapse label="이 질문의 관찰 근거">{evidenceContent}</Collapse>
</article>
```

`evidenceContent`는 기존 q.evidence.items와 signal JSX를 해당 map 안에서 지역 변수로 분리한 것이다.
문장에는 순번을 이어 붙이지 않는다. signal weeks, 원문, source 의미를 유지한다.
- [ ] dirty 입력은 재요청으로 덮어쓰지 않는다. 저장 성공은 라이브 영역, 실패는 내용 보존과 재시도로 표시한다.

```tsx
{saveExtra.isError ? <Notice role="alert">질문을 저장하지 못했습니다. 입력한 내용은 그대로 있습니다. 다시 저장해 주세요.</Notice> : null}
{saveExtra.isSuccess && !dirty ? <Notice role="status">질문을 저장했습니다.</Notice> : null}
```

기존 API가 반환한 오류를 건강 관련 안내로 바꾸지 않는다. 503→200 MSW 응답 두 번으로 입력 보존·재시도 성공을 테스트한다.
5개·200자 제한, trim된 payload, 빈 문구와 질문 0개 테스트를 유지한다.
- [ ] Run: `npm --prefix frontend test -- src/screens/PrepCard.test.tsx src/ui/Collapse.test.tsx`.
375px에서 긴 질문 3개, 추가 질문 5개, signal 근거, 편집 중 저장 버튼의 위계를 확인한다.
Commit: `feat: group appointment questions with their evidence`.

### Task 5: 기록 출처와 모바일·인쇄 읽기 개선

**Files:** Create `ui/ScrollRegion.tsx`, `ui/ObservationValue.tsx`; Modify `screens/Trajectory.tsx`, `Therapist.tsx`, `PrepCard.tsx`, `styles.css`.
**Tests:** Create `ui/ScrollRegion.test.tsx`, `ui/ObservationValue.test.tsx`; Modify `screens/Trajectory.test.tsx`, `Therapist.test.tsx`, `PrepCard.test.tsx`.
**Interfaces:**
- `ScrollRegion({ label, children }: { label: string; children: ReactNode })`
- `ObservationValue({ point }: { point?: Point })` — Point는 기존 lib/types.ts의 타입.

- [ ] 색에 의존하지 않는 출처 테스트를 추가한다.

```tsx
import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ObservationValue } from './ObservationValue';
it('지난 값을 유지한 기록은 글자로 알린다', () => {
  render(<ObservationValue point={{ week: 6, value: 2, label: '지켜보면 됨', source: 'CARRIED' }} />);
  expect(screen.getByText('지켜보면 됨')).toBeVisible();
  expect(screen.getByText('지난 값 유지')).toBeVisible();
});
```

Run: `npm --prefix frontend test -- src/ui/ObservationValue.test.tsx`. Expected before: 파일 부재.
- [ ] Point의 실제 source 값 CONFIRMED/CARRIED를 다음과 같이 표시한다. 모르는 값은 추정해 '직접 확인'으로 바꾸지 않는다.

```tsx
export function ObservationValue({ point }: { point?: Point }) {
  if (!point) return <span>미기록</span>;
  const sourceLabel = point.source === 'CARRIED' ? '지난 값 유지'
    : point.source === 'CONFIRMED' ? '직접 확인' : null;
  return <span className="block" data-carried={point.source === 'CARRIED'}>
    <span>{point.label}</span>
    {sourceLabel ? <span className="block text-small text-ink-soft">{sourceLabel}</span> : null}
  </span>;
}
```

`Point`를 import한다. 출처를 색으로만 구분하는 기존 범례를 '지난 값 유지: 달라진 것 없음으로 이어간 기록'으로 갱신한다.
- [ ] ScrollRegion은 useId로 안내를 연결한다. region과 내부 모두 min-width:0을 확인한다.

```tsx
const hintId = useId();
return <div className="min-w-0">
  <p id={hintId} className="text-small text-ink-soft no-print">좌우로 밀어 주차별 기록을 볼 수 있어요</p>
  <div role="region" aria-label={label} aria-describedby={hintId} tabIndex={0}
    className="scroll-hint overflow-x-auto min-w-0 max-w-full">{children}</div>
</div>;
```

지역 이름은 `${item.label} ${axisLabel} 주차별 기록` 또는 '주차별 관찰 표'로 유일하게 만든다.
Trajectory의 각 Series, PrepCard의 항목 근거, Therapist의 표·수면 기록에 적용한다.
Therapist 수면은 Point 출처가 없으므로 ObservationValue로 강제 변환하지 않는다.
동일 기간 변화 없음 목록, 작성자 변경, 신호·자유 기록·disclaimer는 그대로 유지한다.
- [ ] 치료사 페이지에는 읽기용 별도 넓이와 헤더를 적용하고 인쇄에서 배경·그림자·고정 열·스크롤을 해제한다.
PageHeader의 보호자 설정 링크나 CatalogProvider를 치료사 화면에 추가하지 않는다.
Run: `npm --prefix frontend test -- src/ui/ScrollRegion.test.tsx src/ui/ObservationValue.test.tsx src/screens/Trajectory.test.tsx src/screens/Therapist.test.tsx src/screens/PrepCard.test.tsx`.
375px 표를 키보드로 마지막 주까지 이동하고 인쇄 미리보기에서 같은 값을 확인한다.
Commit: `feat: improve accessible history and therapist reading`.

### Task 6: 설정·이어받기·공유의 완료와 오류 피드백 통일

**Files:** Create `ui/CopyButton.tsx`; Modify `Settings.tsx`, `Recover.tsx`, `Demo.tsx`, `NotFound.tsx`, `TherapistLinkPanel.tsx` 및 각 기존 테스트.
**Tests:** Create `ui/CopyButton.test.tsx`, `screens/NotFound.test.tsx`.
**Interfaces:** `CopyButton({ value, label }: { value: string; label: string })`.

- [ ] CopyButton 실패 시 수동 복사 안내를 확인한다.

```tsx
import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CopyButton } from './CopyButton';
it('복사 거부를 성공으로 표시하지 않는다', async () => {
  const user = userEvent.setup();
  vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValueOnce(new Error('denied'));
  render(<CopyButton value="TEST1234" label="코드 복사하기" />);
  await user.click(screen.getByRole('button', { name: '코드 복사하기' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('직접 선택해 복사해 주세요');
});
```

테스트 후 vi.restoreAllMocks를 실행한다. 성공, clipboard 미지원, value가 바뀐 뒤 성공 안내 초기화도 확인한다.
- [ ] useState/useEffect로 복사 결과를 관리하고 value 변경 시 초기화한다.

```tsx
const [result, setResult] = useState<'idle' | 'done' | 'error'>('idle');
useEffect(() => setResult('idle'), [value]);
async function copy() {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
    await navigator.clipboard.writeText(value);
    setResult('done');
  } catch { setResult('error'); }
}
```

실제 버튼 라벨은 label을 유지한다. 성공은 role=status '복사했습니다', 실패는 role=alert
'복사하지 못했습니다. 표시된 내용을 직접 선택해 복사해 주세요.'로 표시한다. value를 로그로 남기지 않는다.
- [ ] 설정은 다음 진료 → 가족과 기록 이어가기 → 기록 알림 → 치료사 링크 순으로 묶는다.
현재 코드가 있고 '지금 코드 보기'를 누른 경우에만 CopyButton을 노출한다. 코드 없음 안내와
재발급 확인/취소 절차를 유지한다. 재발급 뒤 표시 코드는 새 응답으로 즉시 갱신한다.
진료일 input의 min은 me.today, 저장 후 성공 안내, 실패 시 서버 오류와 재시도를 제공한다.
빈 날짜는 기존 null로 저장하되, 수정하지 않은 날짜를 실수로 null로 보내지 않도록 dateValue를 사용한다.
- [ ] Settings.test에 기존 handler를 사용해 수정 없이 저장하면 원래 날짜가 전달되는지 검사한다.
과거 날짜 실패는 입력을 보존하고, 성공한 새 날짜는 reload 후 표시되는지 검사한다.
서버 과거 날짜 검증은 frontend min만으로 대체하지 않는다.
- [ ] Recover·Demo·NotFound에 PageHeader와 노트 표현을 적용하고 각 실패 동선을 유지한다.
TherapistLinkPanel은 CopyButton으로 복사 피드백을 통일하며 useIssueLink 공유 캐시·재발급 경계를 유지한다.
공유 주소는 fragment 방식이며 기존 preview의 rel=noreferrer를 유지한다.
- [ ] Run: `npm --prefix frontend test -- src/ui/CopyButton.test.tsx src/screens/Settings.test.tsx src/screens/Recover.test.tsx src/screens/Demo.test.tsx src/screens/NotFound.test.tsx src/ui/TherapistLinkPanel.test.tsx`.
한 화면에서 발급한 링크가 다른 화면에서 새로 발급되지 않는 기존 검사를 유지한다.
Commit: `feat: clarify settings recovery and share feedback`.

### Task 7: 전체 시각·동작 검증과 결과 보고

**Files:** Create `frontend/e2e/warm-observation.spec.ts`, `docs/qa/2026-09-15-warm-observation-ui.md`.
Modify `frontend/e2e/critical-flow.spec.ts`의 승인된 문구 locator만 변경한다. API·토큰·캐시 assertion은 유지한다.
필요한 수정은 해당 원인 파일에만 적용한다.

- [ ] 기존 통합 환경 실행 방법 `scripts/ci/integration-e2e.sh`를 읽고 사용한다. 먼저 Node·Java·Docker 및 점유 포트를 확인한다.
새 외부 서비스나 운영 데이터를 검증용으로 사용하지 않는다.

```bash
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
bash scripts/ci/integration-e2e.sh
```

통합 스크립트가 실행한 기존 critical-flow와 새 spec의 결과를 구분해 기록한다. 환경 미비로 실행하지 못하면
fixture 테스트 성공만으로 실서비스 통합 성공을 주장하지 않는다.

- [ ] 새 spec은 기존 fixture를 import해 API를 로컬에서 차단/응답하며 임의 실제 저장을 피한다.
랜딩과 읽기 화면의 가로 overflow, 지역 스크롤, 키보드 포커스를 검증한다. fixture 요청 패턴은 실제 API 경로에 한정한다.

```ts
import { expect, test } from '@playwright/test';
import { catalogFixture } from '../src/test/fixtures';

for (const width of [320, 375, 768, 1280]) {
  test(`landing fits ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 812 });
    await page.route('**/catalog', route => route.fulfill({ json: catalogFixture }));
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '집에서의 관찰을, 다음 진료의 질문으로' })).toBeVisible();
    await expect(page.getByRole('link', { name: '관찰 기록 시작하기' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
}
```

기록 검증은 다음 합성 fixture를 사용한다. 보호자 계정 없이 로컬 치료사 화면에서
주차별 표의 키보드 스크롤과 인쇄 시 overflow 해제를 검증한다.

```ts
import type { TherapistSummary } from '../src/lib/types';

test('모바일에서 마지막 주차까지 키보드로 읽고 인쇄에서 스크롤을 해제한다', async ({ page }) => {
  const token = '11111111-2222-4333-8444-555555555555';
  const summary: TherapistSummary = {
    generatedAt: '2026-09-15T00:00:00Z', weeks: [1, 2, 3, 4, 5, 6],
    items: [{
      code: 'toilet', label: '화장실 이용', changed: true,
      axes: [{ axis: 'LEVEL', axisLabel: '도움 수준', values: [1, 2, 3, 4, 5, 6].map(week => ({
        week, value: 2, label: '지켜보면 됨', source: week === 6 ? 'CARRIED' : 'CONFIRMED',
      })) }],
    }],
    signals: [], sleep: [], signalsEnabled: false, freeNotes: [], questions: [], extraQuestions: [],
    density: { totalWeeks: 6, recordedWeeks: 6, confirmedWeeks: 5, authors: ['딸'] },
    authorChanges: [], disclaimer: '보호자가 집에서 관찰한 기록입니다.',
  };
  await page.setViewportSize({ width: 375, height: 812 });
  await page.route(`**/t/${token}`, route => route.fulfill({ json: summary }));
  await page.goto(`/t#${token}`);
  const region = page.getByRole('region', { name: '주차별 관찰 표' });
  await expect(region).toBeVisible();
  await region.focus();
  for (let i = 0; i < 30; i++) await page.keyboard.press('ArrowRight');
  await expect(region.getByRole('columnheader', { name: '6주', exact: true })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.emulateMedia({ media: 'print' });
  await expect(region).toHaveCSS('overflow-x', 'visible');
});
```

이 검사는 실제 A4 페이지 분할까지 보증하지 않는다. 별도로 인쇄 미리보기 또는 PDF에서
첫 주·마지막 주·긴 메모·다음 페이지의 제목이 잘리지 않는지 확인하고 결과에 기록한다.

- [ ] 스크린샷은 375px·768px·1280px에서 랜딩/미기록 홈/가까운 진료 홈/변화 없음 홈/입력/준비 카드/전체 기록/설정/치료사 보기로 남긴다.
파일명에 화면·상태·너비를 포함하고 코드·토큰은 노출하지 않는다. 비교 기준은 구현 전 캡처와 같은 상태·너비다.
- [ ] 200% 확대는 실제 브라우저 확대 또는 글자 확대 설정에서 검사한다. viewport만 줄인 검사는 확대 검증으로 기록하지 않는다.
키보드만으로 선택·다음·뒤로·근거 펼치기·표 스크롤·재시도를 수행한다. 입력 키보드 상태는 실제 모바일 또는
가능한 에뮬레이션 범위를 명시하고 확인하지 못한 실기 조건을 보고서에 남긴다.
- [ ] 흑백에서도 선택·기록 출처를 구별하는지, 토큰 대비 계산과 실제 배경이 일치하는지 확인한다.
반드시 본문뿐 아니라 보조 글자·입력 경계·focus ring도 측정한다. 줄임표로 서버 원문이 잘리지 않는지 확인한다.
- [ ] 결과 보고서에 검증 명령·성공/실패·대표 변경 전후 이미지·남은 한계·수정 파일을 기록한다.
실제 사용자 연구를 하지 않았다면 '40~70대 사용자 검증 완료'라고 쓰지 않는다.
- [ ] 마지막 변경 이후 관련 테스트를 재실행하고 전체 타입·테스트·빌드와 통합 결과를 확인한다.
`git diff --check` 후 작업 파일만 커밋하고 실행 결과와 검토 가능한 브랜치/화면을 전달한다.
Commit: `test: verify warm observation user journeys and layouts`.

## 명세 커버리지 확인

| 명세 | 작업 |
| --- | --- |
| 1 목표·최신 수정 기반 | Task 1 기준선과 이력 통합 |
| 2 제품 경계·규칙 개정 | Global Constraints, Task 1 문서, 전체 원문 회귀 |
| 3 시각 언어 | Task 1 토큰·공통 UI, Task 7 시각 검증 |
| 4.1 랜딩·4.2 홈 | Task 2 |
| 4.3 입력 흐름 | Task 3 |
| 4.4 준비 카드 | Task 4 |
| 4.5 기록·치료사 | Task 5 |
| 4.6 설정·이어받기·오류 | Task 6 |
| 5 상태·접근성 | Task 1~6 동작, Task 7 전체 검증 |
| 6 구조·데이터 | 파일 지도·각 Interfaces·기존 API 재사용 |
| 7 완료 기준·화면 비교 | Task 7 |

## 인계

이 문서는 구현 계획이다. 앱 UI 변경·테스트 실행·배포가 완료됐다는 보고가 아니다.
실행 방식은 작업별 에이전트와 중간 리뷰 또는 현재 대화의 순차 실행 중 선택한다.
실행자는 계획과 승인 명세를 함께 읽고 완료한 checkbox만 체크한다.
