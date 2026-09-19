import { useState } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { QuestionnaireEditor } from './Questionnaire';
import { feedingQuestionnaire } from '../test/questionnaireFixtures';
import { EMPTY_ITEM, questionnaireComplete } from '../lib/questionnaire';
import type { ItemInput } from '../lib/types';

function Example() {
  const [value, setValue] = useState<ItemInput>(EMPTY_ITEM);
  return <>
    <QuestionnaireEditor form={feedingQuestionnaire} value={value} onChange={setValue} />
    <button disabled={!questionnaireComplete(feedingQuestionnaire, value.answers ?? {})}>다음</button>
    <output aria-label="전송할 답변">{JSON.stringify(value)}</output>
  </>;
}

describe('활동별 질문 화면', () => {
  it('먹는 방법에 따라 질문을 열고 숨기며 오래된 응답을 없앤다', async () => {
    const user = userEvent.setup();
    render(<Example />);
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    expect(screen.queryByText('음식을 드실 때 어떤 도움이 필요했나요?')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '입으로 드셨어요' }));
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '일부 동작을 도왔어요' }));
    await user.click(screen.getByRole('button', { name: '음식 뜨기' }));
    await user.click(screen.getByRole('button', { name: '입으로 가져가기' }));
    const detail = screen.getByRole('group', { name: /어떤 동작을 도왔나요/ });
    expect(within(detail).getByRole('button', { name: '음식 뜨기' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: '잘 모르겠어요' }));
    expect(within(detail).getByRole('button', { name: '음식 뜨기' })).toHaveAttribute('aria-pressed', 'false');
    await user.click(screen.getByRole('button', { name: '영양관을 사용했어요' }));
    expect(screen.queryByText('음식을 드실 때 어떤 도움이 필요했나요?')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
    const payload = JSON.parse(screen.getByLabelText('전송할 답변').textContent!);
    expect(payload.answers).toEqual({ route: ['tube'] });
    expect(payload.level).toBeNull();
  });
  it('다른 관찰을 메모로 남길 수 있고 글자 수 상한을 알린다', async () => {
    const user = userEvent.setup();
    render(<Example />);
    const note = screen.getByRole('textbox', { name: /추가로 남길 관찰/ });
    expect(note).toHaveAttribute('maxlength', '500');
    await user.type(note, '국은 혼자 드셨어요.');
    expect(JSON.parse(screen.getByLabelText('전송할 답변').textContent!).note).toBe('국은 혼자 드셨어요.');
  });
});
