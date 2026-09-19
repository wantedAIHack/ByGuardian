import { expect, test } from '@playwright/test';
import type { Catalog } from '../src/lib/types';

const API = 'http://127.0.0.1:18080';

test('judge completes onboarding and advances a private demo timeline toward the chosen visit', async ({ page, request }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const catalog = await (await request.get(`${API}/catalog`)).json() as Catalog;

  await page.goto('/demo');
  await expect(page.getByText(/온보딩과 1주차 관찰부터 직접/)).toBeVisible();
  await page.getByRole('link', { name: '온보딩부터 시작하기' }).click();
  await page.getByRole('button', { name: '시작', exact: true }).click();

  for (const choice of ['딸', '뇌졸중', '오른쪽', '잘 하심']) {
    await page.getByRole('button', { name: choice, exact: true }).click();
    await page.getByRole('button', { name: '다음', exact: true }).click();
  }
  await page.getByLabel('다음 진료일').fill('2026-09-19');
  await page.getByRole('button', { name: '다음', exact: true }).click();
  await page.getByRole('button', { name: '시작', exact: true }).click();

  for (const item of catalog.items) {
    await expect(page.getByRole('heading', { name: item.label, exact: true })).toBeVisible();
    if (item.questionnaire) {
      for (const question of item.questionnaire.questions.filter((q) => q.required)) {
        const group = page.getByRole('group', { name: question.label, exact: true });
        if (await group.count() === 0) continue;
        const option = question.code === 'route'
          ? question.options.find((o) => o.code === 'oral')!
          : question.options[0]!;
        await group.getByRole('button', { name: option.label, exact: true }).click();
      }
    } else {
      for (const axis of item.axes) {
        const options = catalog.axes[axis]!;
        await page.getByRole('button', { name: options.at(-1)!.label, exact: true }).click();
      }
    }
    await page.getByRole('button', { name: '다음', exact: true }).click();
  }

  await expect(page.getByRole('heading', { name: '이어받기 코드' })).toBeVisible();
  await page.getByRole('button', { name: '적어뒀습니다' }).click();
  await page.getByRole('button', { name: '알겠습니다' }).click();
  await page.getByRole('button', { name: '나중에 하기' }).click();

  await expect(page.getByLabel('데모 진행')).toContainText('1주차');
  await expect(page.getByLabel('데모 진행')).toContainText('2026.09.05');
  expect(await page.evaluate(() => ({
    demo: sessionStorage.getItem('nextvisit.demo-token'),
    ordinary: localStorage.getItem('guardianToken'),
  }))).toMatchObject({ ordinary: null });

  await page.getByRole('button', { name: '다음 주차로 이동' }).click();
  await expect(page.getByLabel('데모 진행')).toContainText('2주차');
  await expect(page.getByLabel('데모 진행')).toContainText('2026.09.12');
  await expect(page.getByRole('button', { name: '다음 주차로 이동' })).toBeDisabled();

  await page.getByRole('link', { name: '3분 기록하기' }).click();
  await page.getByRole('button', { name: '없어요', exact: true }).click();
  await page.getByRole('button', { name: '잘 주무심', exact: true }).click();
  await page.getByRole('button', { name: '다음', exact: true }).click();
  await page.getByRole('button', { name: '저장하기', exact: true }).click();
  await expect(page.getByRole('heading', { name: '기록을 남겼습니다.' })).toBeVisible();
  await page.getByRole('button', { name: '홈으로' }).click();

  await expect(page.getByRole('button', { name: '다음 주차로 이동' })).toBeEnabled();
  await page.getByRole('button', { name: '다음 주차로 이동' }).click();
  await expect(page.getByLabel('데모 진행')).toContainText('3주차');
  await expect(page.getByLabel('데모 진행')).toContainText('2026.09.19');
  await expect(page.getByRole('link', { name: '진료 준비 카드 보기' })).toBeVisible();

  await page.getByRole('button', { name: '치료사에게 보여드리기', exact: true }).click();
  const preview = page.getByRole('link', { name: '어떻게 보이는지 확인하기' });
  const popup = page.waitForEvent('popup');
  await preview.click();
  const therapist = await popup;
  await expect(therapist.getByRole('heading', { name: '가정 관찰 기록', exact: true })).toBeVisible();
  await expect(therapist.getByText(/1주차 ~ 2주차/)).toBeVisible();

  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
