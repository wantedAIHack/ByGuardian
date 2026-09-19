import { expect, test } from '@playwright/test';
import type { Catalog, OnboardingRequest, OnboardingResponse, Trajectory } from '../src/lib/types';

const API = 'http://127.0.0.1:18080';
test('mobile baseline saves activity-specific observations without hidden answers and shows them in the report', async ({ page, request }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  // Resume at the observation section; identity screens are covered by existing journeys.
  await page.addInitScript(() => {
    localStorage.setItem('draft:onboarding', JSON.stringify({ step: 7, relation: '딸', relationOther: '',
      diagnosis: 'STROKE', pareticSide: 'LEFT', verbalDifficulty: 'NONE', nextVisitDate: null, items: {} }));
  });
  const catalog = await (await request.get(`${API}/catalog`)).json() as Catalog;
  await page.goto('/onboarding');
  const created = page.waitForResponse((r) => r.url() === `${API}/cases` && r.request().method() === 'POST');
  for (const item of catalog.items) {
    await expect(page.getByRole('heading', { name: item.label, exact: true })).toBeVisible();
    const form = item.questionnaire;
    if (!form) {
      for (const axis of item.axes) {
        const options = catalog.axes[axis]!;
        await page.getByRole('button', { name: options[options.length - 1]!.label, exact: true }).click();
      }
    } else {
      for (const q of form.questions.filter((q) => q.required)) {
        const group = page.getByRole('group', { name: q.label, exact: true });
        if (await group.count() === 0) continue;
        const code = q.code === 'route' ? 'oral'
          : item.code === 'grooming' ? (q.code === 'washing' ? 'unknown' : 'not_performed')
          : q.code === 'assistance' || q.code === 'clothing' ? '2' : '4';
        await group.getByRole('button', { name: q.options.find((o) => o.code === code)!.label, exact: true }).click();
      }
      if (item.code === 'toilet') {
        const management = form.questions.find((q) => q.code === 'management')!;
        await page.locator('summary').filter({ hasText: management.label }).click();
        await page.getByRole('button', { name: management.options.find((o) => o.code === 'catheter')!.label, exact: true }).click();
        const night = form.questions.find((q) => q.code === 'night')!;
        await page.locator('summary').filter({ hasText: night.label }).click();
        await page.getByRole('group', { name: `${night.label} (선택)`, exact: true })
          .getByRole('button', { name: night.options.find((o) => o.code === 'yes')!.label, exact: true }).click();
        const help = form.questions.find((q) => q.code === 'night_help')!;
        await page.getByRole('group', { name: `${help.label} (선택)`, exact: true })
          .getByRole('button', { name: help.options.find((o) => o.code === 'physical')!.label, exact: true }).click();
        await page.getByRole('textbox', { name: /추가로 남길 관찰/ }).fill('밤에 바지를 올릴 때 도왔어요.');
        await page.screenshot({ path: testInfo.outputPath('toilet-mobile.png'), fullPage: true });
      }
      if (item.code === 'dressing') {
        const parts = form.questions.find((q) => q.code === 'parts')!;
        await page.getByRole('button', { name: parts.options.find((o) => o.code === 'upper')!.label, exact: true }).click();
      }
      if (item.code === 'feeding') {
        const parts = form.questions.find((q) => q.code === 'parts')!;
        await page.getByRole('button', { name: parts.options[0]!.label, exact: true }).click();
        const route = form.questions.find((q) => q.code === 'route')!;
        await page.getByRole('button', { name: route.options.find((o) => o.code === 'tube')!.label, exact: true }).click();
        await expect(page.getByRole('group', { name: /입으로 드실 때 어느 정도/ })).toHaveCount(0);
      }
    }
    await expect(page.getByRole('button', { name: '다음', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: '다음', exact: true }).click();
  }
  const response = await created;
  expect(response.status()).toBe(201);
  const body = response.request().postDataJSON() as OnboardingRequest;
  expect(body.baseline.items.feeding).toMatchObject({ questionnaireVersion: 2, answers: { route: ['tube'] }, level: null });
  expect(body.baseline.items.feeding?.answers?.assistance).toBeUndefined();
  expect(body.baseline.items.feeding?.answers?.parts).toBeUndefined();
  expect(body.baseline.items.grooming?.answers).toEqual({ washing: ['unknown'], brushing: ['not_performed'] });
  expect(body.baseline.items.toilet?.answers?.management).toEqual(['catheter']);
  expect(body.baseline.items.toilet?.answers?.night_help).toEqual(['physical']);
  const account = await response.json() as OnboardingResponse;
  const headers = { 'X-Guardian-Token': account.guardianToken };
  const me = await (await request.get(`${API}/me`, { headers })).json();
  expect(me.questionnaireUpgradeRequired).toBe(false);
  const trajectory = await (await request.get(`${API}/me/trajectory`, { headers })).json() as Trajectory[];
  expect(trajectory.find((i) => i.code === 'toilet:v2')?.observations?.find((o) => o.question === 'note')?.answers)
    .toEqual(['밤에 바지를 올릴 때 도왔어요.']);
  await page.goto('/trajectory');
  await expect(page.getByText('밤에 바지를 올릴 때 도왔어요.', { exact: true })).toBeVisible();
  await expect(page.getByText('직접 보지 못함', { exact: true })).toBeVisible();
  await expect(page.getByText('이번 주 하지 않음', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
