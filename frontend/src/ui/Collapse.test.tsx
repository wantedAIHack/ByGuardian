import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Collapse } from './Collapse';

it('펼치기 버튼을 해당 본문에 연결하고 접으면 본문을 숨긴다', async () => {
  const user = userEvent.setup();
  render(<>
    <Collapse label="관찰 근거"><p>직접 관찰한 내용</p></Collapse>
    <Collapse label="다른 근거"><p>또 다른 관찰 내용</p></Collapse>
  </>);
  const trigger = screen.getByRole('button', { name: '관찰 근거' });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(screen.queryByText('직접 관찰한 내용')).not.toBeInTheDocument();

  await user.click(trigger);
  expect(trigger).toHaveAttribute('aria-expanded', 'true');
  expect(trigger).toHaveAccessibleName('관찰 근거 접기');
  const panelId = trigger.getAttribute('aria-controls');
  expect(panelId).toBeTruthy();
  expect(document.getElementById(panelId!)).toContainElement(screen.getByText('직접 관찰한 내용'));

  const other = screen.getByRole('button', { name: '다른 근거' });
  await user.click(other);
  expect(other.getAttribute('aria-controls')).not.toBe(panelId);
  await user.click(trigger);
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(screen.queryByText('직접 관찰한 내용')).not.toBeInTheDocument();
  expect(screen.getByText('또 다른 관찰 내용')).toBeVisible();
});
