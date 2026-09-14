import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import type { PrepCard, Trajectory, WeeklyRecordResponse } from '../src/lib/types';

const FE = 'http://127.0.0.1:14173';
const API = 'http://127.0.0.1:18080';
const STORAGE_KEY = 'nextvisit.therapist-token';
const NOTE = '이번 주 통합 검증: 화장실에서 손을 잡아드렸습니다.';
const deadLink = '이 주소는 더 이상 열리지 않습니다. 보호자분께 새 주소를 받아주세요.';

async function noApiCache(page: Page) {
  const origins = await page.evaluate(async () => {
    const urls: string[] = [];
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) {
        urls.push(new URL(request.url).origin);
      }
    }
    return urls;
  });
  expect(origins).not.toContain(API);
}

test('real six-week record, prep card, and private therapist sharing with LLM off', async ({ page, context, browser, request }) => {
  const unexpectedOrigins: string[] = [];
  const watch = (target: BrowserContext) => target.on('request', (req) => {
    const origin = new URL(req.url()).origin;
    if (![FE, API].includes(origin)) unexpectedOrigins.push(origin);
  });
  watch(context);
  await page.goto('/demo');
  const demo = page.waitForResponse((r) => r.url() === `${API}/demo` && r.request().method() === 'POST');
  await page.getByRole('button', { name: '데모 기록 만들기', exact: true }).click();
  expect((await demo).status()).toBe(201);
  await expect(page.getByText('여섯 주치 관찰이 들어 있습니다.', { exact: true })).toBeVisible();
  const initialPrepResponse = page.waitForResponse((r) => r.url() === `${API}/me/prep-card`);
  await page.getByRole('link', { name: '보호자 화면 보기' }).click();
  await expect(page.getByText('이번 주 기록을 남기셨어요 ✓', { exact: true })).toBeVisible();
  const initialPrep = await (await initialPrepResponse).json() as PrepCard;
  const initialToilet = initialPrep.questions.flatMap((q) => q.evidence.items)
    .find((item) => item.code === 'toilet' && item.axis === 'LEVEL');
  expect(initialToilet?.values.find((v) => v.week === 6)).toMatchObject({ value: 3, source: 'CONFIRMED' });

  // The seeded current week is already recorded; open its real editor to update it.
  await page.goto('/record');
  await page.getByRole('button', { name: '네, 달라진 게 있어요', exact: true }).click();
  await page.getByRole('button', { name: '화장실 이용', exact: true }).click();
  await page.getByRole('button', { name: '다음', exact: true }).click();
  await page.getByRole('button', { name: '손 잡아드림', exact: true }).click();
  await page.getByRole('button', { name: '매번', exact: true }).click();
  await page.getByRole('button', { name: '다음', exact: true }).click();
  await page.getByRole('button', { name: '없었어요', exact: true }).click();
  await page.getByRole('button', { name: '잘 주무심', exact: true }).click();
  await page.getByRole('button', { name: '다음', exact: true }).click();
  await page.getByRole('textbox', { name: '말씀하시듯 편하게 적어주세요' }).fill(NOTE);
  const saved = page.waitForResponse((r) => r.url() === `${API}/me/weeks/6` && r.request().method() === 'PUT');
  await page.getByRole('button', { name: '저장하기', exact: true }).click();
  const savedResponse = await saved;
  expect(savedResponse.status()).toBe(200);
  expect(await savedResponse.json() as WeeklyRecordResponse).toMatchObject({ week: 6, kind: 'WEEKLY', questionsRefreshed: true });
  await expect(page.getByText('기록을 남겼습니다.', { exact: true })).toBeVisible();
  const prepResponse = page.waitForResponse((r) => r.url() === `${API}/me/prep-card`);
  await page.getByRole('button', { name: '홈으로', exact: true }).click();
  const prep = await (await prepResponse).json() as PrepCard;
  expect(prep.week).toBe(6);
  expect(prep.questions.length).toBeGreaterThan(0);
  expect(prep.questions.every((q) => q.source === 'TEMPLATE')).toBe(true);
  // Changing week 6 breaks the seeded sustained rise, so toilet must leave the
  // selected prep questions. Independently prove the new value was persisted.
  expect(prep.questions.flatMap((q) => q.evidence.items).some((item) => item.code === 'toilet')).toBe(false);
  expect(prep.questions.map((q) => q.sentence)).not.toEqual(initialPrep.questions.map((q) => q.sentence));
  const trajectoryResponse = page.waitForResponse((r) => r.url() === `${API}/me/trajectory`);
  await page.getByRole('link', { name: '전체 기록 보기', exact: true }).click();
  const trajectory = await (await trajectoryResponse).json() as Trajectory[];
  const changed = trajectory.find((item) => item.code === 'toilet')?.axes.find((axis) => axis.axis === 'LEVEL');
  expect(changed?.values.find((v) => v.week === 6)).toMatchObject({ value: 1, source: 'CONFIRMED' });
  await page.goBack();
  await page.getByRole('link', { name: '진료 준비 카드 보기' }).click();
  for (const question of prep.questions) await expect(page.getByText(question.sentence, { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '근거 보기' }).first().click();
  await expect(page.getByText('6주', { exact: true }).first()).toBeVisible();

  await page.getByRole('button', { name: '치료사에게 보여드리기', exact: true }).click();
  const preview = page.getByRole('link', { name: '어떻게 보이는지 확인하기' });
  await expect(preview).toHaveAttribute('href', new RegExp(`^${FE}/t#[0-9a-f-]{36}$`));
  const shared = (await preview.getAttribute('href'))!;
  const token = new URL(shared).hash.slice(1);
  await expect(page.getByText(shared, { exact: true })).toBeVisible();

  // Observe the actual document request and the fragment at the first API fetch.
  // This instrumentation never supplies a response or changes application state.
  await context.addInitScript(({ api }) => {
    const original = window.fetch;
    (window as unknown as { therapistFetchHashes: string[] }).therapistFetchHashes = [];
    window.fetch = function (...args) {
      const url = args[0] instanceof Request ? args[0].url : String(args[0]);
      if (url.startsWith(`${api}/t/`)) {
        (window as unknown as { therapistFetchHashes: string[] }).therapistFetchHashes.push(location.hash);
      }
      return original.apply(this, args);
    };
  }, { api: API });
  const documents: string[] = [];
  context.on('request', (req) => {
    if (req.isNavigationRequest() && new URL(req.url()).origin === FE) documents.push(req.url());
  });
  const popup = page.waitForEvent('popup');
  await preview.click();
  const therapist = await popup;
  await expect(therapist.getByRole('heading', { name: '가정 관찰 기록', exact: true })).toBeVisible();
  expect(documents[0]).toBe(`${FE}/t`);
  expect(documents.every((url) => !url.includes(token))).toBe(true);
  await expect(therapist).toHaveURL(`${FE}/t`);
  const hashes = await therapist.evaluate(() => (window as unknown as { therapistFetchHashes: string[] }).therapistFetchHashes);
  expect(hashes.length).toBeGreaterThan(0);
  expect(hashes.every((hash) => hash === '')).toBe(true);
  await expect(therapist.getByText(NOTE, { exact: true })).toBeVisible();
  expect(await therapist.evaluate((key) => sessionStorage.getItem(key), STORAGE_KEY)).toBe(token);
  await therapist.reload();
  await expect(therapist.getByText(NOTE, { exact: true })).toBeVisible();
  await expect(therapist).toHaveURL(`${FE}/t`);
  await therapist.goto(`${FE}/demo`);
  await therapist.goBack();
  await expect(therapist.getByText(NOTE, { exact: true })).toBeVisible();
  await expect(therapist).toHaveURL(`${FE}/t`);
  await therapist.goForward();
  await expect(therapist).toHaveURL(`${FE}/demo`);
  await therapist.goBack();
  await expect(therapist).toHaveURL(`${FE}/t`);
  await expect(therapist.getByText(NOTE, { exact: true })).toBeVisible();

  const plainSibling = await context.newPage();
  await plainSibling.goto(`${FE}/t`);
  await expect(plainSibling.getByText(deadLink, { exact: true })).toBeVisible();
  expect(await plainSibling.evaluate((key) => sessionStorage.getItem(key), STORAGE_KEY)).toBeNull();
  const sibling = await context.newPage();
  await sibling.goto(shared);
  await expect(sibling.getByText(NOTE, { exact: true })).toBeVisible();
  await expect(sibling).toHaveURL(`${FE}/t`);
  await sibling.evaluate((key) => sessionStorage.removeItem(key), STORAGE_KEY);
  await sibling.reload();
  await expect(sibling.getByText(deadLink, { exact: true })).toBeVisible();
  await therapist.reload();
  await expect(therapist.getByText(NOTE, { exact: true })).toBeVisible();

  const isolated = await browser.newContext();
  watch(isolated);
  try {
    const separatePlain = await isolated.newPage();
    await separatePlain.goto(`${FE}/t`);
    await expect(separatePlain.getByText(deadLink, { exact: true })).toBeVisible();
    expect(await separatePlain.evaluate((key) => sessionStorage.getItem(key), STORAGE_KEY)).toBeNull();
    const separate = await isolated.newPage();
    await separate.goto(shared);
    await expect(separate.getByText(NOTE, { exact: true })).toBeVisible();
    await expect(separate).toHaveURL(`${FE}/t`);
    await noApiCache(separate);
  } finally {
    await isolated.close();
  }
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => true));
  await noApiCache(page);
  await noApiCache(therapist);
  expect(unexpectedOrigins).toEqual([]);
  const preflight = await request.fetch(`${API}/me`, {
    method: 'OPTIONS',
    headers: { Origin: 'https://not-allowed.invalid', 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'authorization' },
  });
  expect(preflight.headers()['access-control-allow-origin']).toBeUndefined();
  const health = await request.get(`${API}/health`);
  expect(health.status()).toBe(200);
  expect(await health.json()).toEqual({ status: 'ok', db: 'up' });
});
