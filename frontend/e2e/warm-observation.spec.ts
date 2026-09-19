import { expect, test, type Page } from '@playwright/test';
import { catalogFixture, me } from '../src/test/fixtures';
import type { PrepCard, TherapistSummary } from '../src/lib/types';

test.use({ baseURL: process.env.UI_TEST_BASE_URL ?? 'http://127.0.0.1:14173' });

const token = '11111111-2222-4333-8444-555555555555';
const summary: TherapistSummary = {
  generatedAt: '2026-09-15T00:00:00Z', weeks: [1, 2, 3, 4, 5, 6],
  items: [{
    code: 'toilet', label: '화장실 이용', changed: true,
    axes: [{ axis: 'LEVEL', axisLabel: '도움 수준', values: [1, 2, 3, 4, 5, 6].map(week => ({
      week, value: 2, label: '지켜보면 됨', source: week === 6 ? 'CARRIED' : 'CONFIRMED',
    })) }],
  }],
  signals: [], sleep: [], signalsEnabled: false, freeNotes: [],
  questions: [], extraQuestions: [],
  density: { totalWeeks: 6, recordedWeeks: 6, confirmedWeeks: 5, authors: ['딸'] },
  authorChanges: [], disclaimer: '보호자가 집에서 관찰한 기록입니다.',
};
const prepEvidence = {
  items: [{
    code: 'ambulation', label: '집 안에서 걷기', axis: 'LEVEL', axisLabel: '도움 수준',
    values: [{ week: 5, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
  }],
  signal: null,
};
const prepCard: PrepCard = {
  week: 6, nextVisitDate: null,
  questions: [{
    rank: 1, type: 'PLATEAU', source: 'TEMPLATE',
    sentence: '집 안에서 걷기는 왜 안 늘고 있을까요?', evidence: prepEvidence,
  }],
  extraQuestions: [], emptyMessage: null, therapistGlance: [],
  generationStatus: 'TEMPLATE_ONLY', edited: false, suggestionAvailable: false,
  items: [{
    id: 'q1-a', sentence: '집 안에서 걷기는 왜 안 늘고 있을까요?',
    origin: 'TEMPLATE', edited: false,
    basis: {
      evidence: prepEvidence,
      notes: [{ week: 3, timeTagLabel: '오후', itemLabel: null, text: '합성 원문 메모' }],
    },
  }],
};

async function catalog(page: Page) {
  await page.route('**/catalog', route => route.fulfill({ json: catalogFixture }));
}
async function fits(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}
async function doubleText(page: Page) {
  await page.evaluate(() => {
    const sizes = [...document.querySelectorAll<HTMLElement>('body, body *')]
      .map(el => [el, parseFloat(getComputedStyle(el).fontSize)] as const);
    for (const [el, size] of sizes) el.style.fontSize = `${size * 2}px`;
  });
}
for (const width of [320, 375, 768, 1280]) {
  test(`랜딩과 치료사 화면이 ${width}px에 맞는다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 812 });
    await catalog(page);
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '집에서의 관찰을, 다음 진료의 질문으로' })).toBeVisible();
    await expect(page.getByRole('link', { name: '관찰 기록 시작하기' })).toBeVisible();
    await fits(page);
    await page.route(`**/t/${token}`, route => route.fulfill({ json: summary }));
    await page.goto(`/t#${token}`);
    await expect(page.getByRole('region', { name: '주차별 관찰 표' })).toBeVisible();
    await fits(page);
  });
}

test('모바일 표의 마지막 주차까지 키보드로 읽고 인쇄에서는 스크롤을 해제한다', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.route(`**/t/${token}`, route => route.fulfill({ json: summary }));
  await page.goto(`/t#${token}`);
  const region = page.getByRole('region', { name: '주차별 관찰 표' });
  await region.focus();
  for (let i = 0; i < 30; i++) await page.keyboard.press('ArrowRight');
  await expect(region.getByRole('columnheader', { name: '6주', exact: true })).toBeInViewport();
  await expect(region.getByText('지난 값 유지', { exact: true })).toBeInViewport();
  await fits(page);
  await page.emulateMedia({ media: 'print' });
  await expect(region).toHaveCSS('overflow-x', 'visible');
  await expect(region.locator('th').first()).toHaveCSS('position', 'static');
});

test('질문 이동에만 초점을 옮기고 작은 높이에서도 다음 버튼에 접근한다', async ({ page }) => {
  await catalog(page);
  await page.setViewportSize({ width: 375, height: 400 });
  await page.addInitScript(() => {
    localStorage.setItem('draft:onboarding', JSON.stringify({
      step: 7, relation: '딸', relationOther: '', diagnosis: 'STROKE',
      pareticSide: 'LEFT', verbalDifficulty: 'NONE', nextVisitDate: null, items: {},
    }));
  });
  await page.goto('/onboarding');
  const heading = page.getByRole('heading', { level: 1 });
  await expect(heading).toBeFocused();
  await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '1');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: '대부분 도움', exact: true })).toBeFocused();
  await expect(page.getByRole('button', { name: '대부분 도움', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Tab'); await page.keyboard.press('Tab'); await page.keyboard.press('Tab'); await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: '휠체어', exact: true })).toHaveAttribute('aria-pressed', 'true');
  for (let i = 0; i < 5; i++) await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: '좋은 날만', exact: true })).toHaveAttribute('aria-pressed', 'true');
  for (let i = 0; i < 3; i++) await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: '다음', exact: true })).toBeFocused();
  await expect(page.getByRole('button', { name: '다음', exact: true })).toBeInViewport();
  await expect(page.locator('.flow-footer')).toHaveCSS('position', 'static');
  await page.keyboard.press('Enter');
  await expect(heading).toHaveText('집 안에서 걷기');
  await expect(heading).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(page.getByRole('button', { name: '← 뒤로' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(heading).toHaveText('침대·의자에서 옮겨 앉기');
  await expect(heading).toBeFocused();
  await expect(page.getByRole('button', { name: '대부분 도움', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await fits(page);
});

test('홈에서 실패한 조회를 변화 없음으로 안내하지 않고 재시도한다', async ({ page }) => {
  await catalog(page);
  await page.addInitScript(() => localStorage.setItem('guardianToken', 'ui-test-token'));
  await page.route('**/me', route => route.fulfill({ json: me({ recordedThisWeek: true, nextVisitDate: null }) }));
  await page.route('**/me/progress', route => route.fulfill({ status: 503, body: '' }));
  await page.goto('/');
  await expect(page.getByRole('alert')).toHaveText('관찰 내용을 불러오지 못했습니다.');
  await expect(page.getByText('이번 기간에는 바뀐 항목이 없습니다.', { exact: true })).toHaveCount(0);
  await page.route('**/me/progress', route => route.fulfill({ json: { week: 6, silent: true, changes: [], transitions: [], questions: [] } }));
  await page.getByRole('button', { name: '다시 시도하기' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('이번 기간에는 바뀐 항목이 없습니다.', { exact: true })).toBeVisible();
});

test('200% 글자 확대에서도 랜딩을 읽고 시작할 수 있다', async ({ page }) => {
  await catalog(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/');
  const heading = page.getByRole('heading', { level: 1 });
  await expect(heading).toBeVisible();
  const before = await heading.evaluate(el => parseFloat(getComputedStyle(el).fontSize));
  // 사용자 스타일시트 방식의 실제 글자 확대: viewport 축소나 pinch 확대가 아니다.
  await doubleText(page);
  expect(await heading.evaluate(el => parseFloat(getComputedStyle(el).fontSize))).toBe(before * 2);
  await fits(page);
  await page.getByRole('link', { name: '관찰 기록 시작하기' }).click();
  await expect(page.getByRole('heading', { name: '잠깐만 여쭤보겠습니다' })).toBeVisible();
});

test('입력·설정·질문·기록을 200% 글자로 읽어도 페이지가 옆으로 잘리지 않는다', async ({ page }) => {
  await catalog(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.addInitScript(() => localStorage.setItem('draft:onboarding', JSON.stringify({
    step: 7, relation: '딸', relationOther: '', diagnosis: 'STROKE',
    pareticSide: 'LEFT', verbalDifficulty: 'NONE', nextVisitDate: null, items: {},
  })));
  await page.route('**/me', route => route.fulfill({ json: me() }));
  await page.route('**/me/prep-card', route => route.fulfill({ json: prepCard }));
  await page.route(`**/t/${token}`, route => route.fulfill({ json: summary }));
  for (const path of ['/onboarding', '/settings', '/prep-card', `/t#${token}`]) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    if (path === '/settings') await expect(page.getByLabel('다음 진료일')).toBeVisible();
    if (path === '/prep-card') {
      await page.getByRole('button', { name: '질문 고치기' }).click();
      await expect(page.getByRole('textbox', { name: '질문 1', exact: true })).toBeVisible();
    }
    if (path.startsWith('/t#')) await expect(page.getByRole('region')).toBeVisible();
    await doubleText(page);
    await fits(page);
  }
});

test('긴 질문 5개를 편집 화면에서 여러 줄로 읽고 저장한다', async ({ page }) => {
  await catalog(page);
  await page.setViewportSize({ width: 375, height: 812 });
  const question = '집에서 관찰할 때 어떤 내용을 기록해 가면 도움이 될까요? '.repeat(4);
  let savedBody: { items: Array<{ id: string | null; sentence: string }> } | null = null;
  await page.route('**/me/prep-card/questions', async route => {
    savedBody = route.request().postDataJSON();
    const savedItems = savedBody!.items.map((item, i) => ({
      id: item.id ?? `saved-${i}`,
      sentence: item.sentence,
      origin: item.id ? 'TEMPLATE' as const : 'CAREGIVER' as const,
      edited: true,
      basis: item.id ? prepCard.items![0]!.basis : { evidence: { items: [], signal: null }, notes: [] },
    }));
    await route.fulfill({ json: { ...prepCard, edited: true, items: savedItems } });
  });
  await page.route('**/me/prep-card', route => route.fulfill({ json: prepCard }));
  await page.goto('/prep-card');
  const toggle = page.getByRole('button', { name: '이 질문의 근거', exact: true });
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('region', { name: '집 안에서 걷기 도움 수준 주차별 기록' })).toBeVisible();
  await expect(page.getByText('손 잡아드림', { exact: true })).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await page.getByRole('button', { name: '질문 고치기' }).click();
  for (let i = 2; i <= 6; i++) {
    await page.getByRole('button', { name: '+ 질문 추가' }).click();
    await page.getByRole('textbox', { name: `질문 ${i}`, exact: true }).fill(question);
  }
  for (let i = 2; i <= 6; i++) {
    const input = page.getByRole('textbox', { name: `질문 ${i}`, exact: true });
    await expect(input).toHaveJSProperty('tagName', 'TEXTAREA');
    await input.scrollIntoViewIfNeeded();
    expect(await input.evaluate(el => el.scrollHeight <= el.clientHeight + 1)).toBe(true);
  }
  await fits(page);
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('질문을 저장했습니다.');
  expect(savedBody?.items).toHaveLength(6);
});

test('긴 원문 인쇄는 한 구역 전체를 다음 장으로 밀지 않는다', async ({ page }) => {
  await page.route(`**/t/${token}`, route => route.fulfill({ json: {
    ...summary, freeNotes: [{ week: 6, text: '보호자 원문 기록입니다.\n'.repeat(45), timeTag: null, timeTagLabel: null }],
  } }));
  await page.goto(`/t#${token}`);
  await page.emulateMedia({ media: 'print' });
  const notes = page.locator('section').filter({ has: page.getByRole('heading', { name: '보호자 기록 (원문)' }) });
  await expect(notes).toHaveCSS('break-inside', 'auto');
});

test('진료일을 저장하고 다시 열어도 날짜를 유지한다', async ({ page }) => {
  await catalog(page);
  let date: string | null = '2026-09-20';
  await page.route('**/me', async route => {
    if (route.request().method() === 'PATCH') {
      date = route.request().postDataJSON().nextVisitDate;
    }
    await route.fulfill({ json: me({ nextVisitDate: date }) });
  });
  await page.goto('/settings');
  await page.getByLabel('다음 진료일').fill('2026-10-01');
  await page.getByRole('button', { name: '진료일 저장' }).click();
  await expect(page.getByRole('status')).toHaveText('진료일을 저장했습니다.');
  expect(date).toBe('2026-10-01');
  await page.reload();
  await expect(page.getByLabel('다음 진료일')).toHaveValue('2026-10-01');
  await page.getByLabel('다음 진료일').fill('');
  await page.getByRole('button', { name: '진료일 저장' }).click();
  await expect(page.getByRole('status')).toHaveText('진료일을 저장했습니다.');
  expect(date).toBeNull();
  await page.reload();
  await expect(page.getByLabel('다음 진료일')).toHaveValue('');
});
