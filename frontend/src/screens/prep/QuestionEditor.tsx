import { useRef, useState } from 'react';
import type { PrepItem } from '../../lib/types';
import { useSaveQuestions } from '../../lib/queries';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';

const MAX_ITEMS = 8;
const MAX_LEN = 200;

interface Draft {
  key: number;
  id: string | null;
  sentence: string;
}

export function QuestionEditor({
  items,
  onCancel,
  onSaved,
}: {
  items: PrepItem[];
  onCancel: () => void;
  onSaved: () => void;
}) {
  const save = useSaveQuestions();
  const nextKey = useRef(items.length);
  const version = useRef(0);
  const [drafts, setDrafts] = useState<Draft[]>(
    () => items.map((entry, i) => ({ key: i, id: entry.id, sentence: entry.sentence })),
  );

  const touch = () => { version.current++; };
  const change = (key: number, value: string) => {
    touch();
    setDrafts((current) => current.map((draft) => (
      draft.key === key ? { ...draft, sentence: value.slice(0, MAX_LEN) } : draft
    )));
  };
  const remove = (key: number) => {
    touch();
    setDrafts((current) => current.filter((draft) => draft.key !== key));
  };
  const add = () => {
    touch();
    const key = nextKey.current++;
    setDrafts((current) => [...current, { key, id: null, sentence: '' }]);
  };
  const submit = () => {
    const submittedVersion = version.current;
    const submitted = drafts
      .map((draft) => ({ ...draft, sentence: draft.sentence.trim() }))
      .filter((draft) => draft.sentence.length > 0);
    save.mutate(
      submitted.map(({ id, sentence }) => ({ id, sentence })),
      {
        onSuccess: (card) => {
          if (submittedVersion === version.current) {
            onSaved();
            return;
          }

          const returnedItems = card.items ?? [];
          const matchesSubmission = returnedItems.length === submitted.length
            && returnedItems.every((returned, i) => returned.sentence === submitted[i]!.sentence);
          if (!matchesSubmission) return;

          const returnedIdByKey = new Map<number, string>();
          submitted.forEach((draft, i) => {
            const returned = returnedItems[i];
            if (returned) returnedIdByKey.set(draft.key, returned.id);
          });
          setDrafts((current) => current.map((draft) => {
            const returnedId = returnedIdByKey.get(draft.key);
            return returnedId === undefined ? draft : { ...draft, id: returnedId };
          }));
        },
      },
    );
  };

  return (
    <section className="note-surface" aria-labelledby="question-editor-title">
      <h2 id="question-editor-title" className="font-semibold">질문 고치기</h2>
      <ol className="flex flex-col gap-4 pt-4">
        {drafts.map((draft, i) => (
          <li key={draft.key} className="flex flex-col gap-2">
            <textarea
              rows={3}
              aria-label={`질문 ${i + 1}`}
              className="min-h-[96px] w-full resize-y rounded-xl border border-control bg-paper p-4 [field-sizing:content]"
              value={draft.sentence}
              maxLength={MAX_LEN}
              onChange={(event) => change(draft.key, event.target.value)}
            />
            <Button
              variant="plain"
              aria-label={`질문 ${i + 1} 지우기`}
              onClick={() => remove(draft.key)}
            >
              지우기
            </Button>
          </li>
        ))}
      </ol>
      <div className="flex flex-col gap-3 pt-4">
        {drafts.length < MAX_ITEMS
          ? <Button variant="plain" onClick={add}>+ 질문 추가</Button>
          : <Notice>여덟 개까지 넣으실 수 있습니다.</Notice>}
        <Button disabled={save.isPending} onClick={submit}>
          {save.isPending ? '저장하는 중입니다…' : '저장'}
        </Button>
        <Button variant="plain" disabled={save.isPending} onClick={onCancel}>취소</Button>
      </div>
      {save.isError ? (
        <Notice role="alert">
          질문을 저장하지 못했습니다. 입력한 내용은 그대로 있습니다. 다시 저장해 주세요.
        </Notice>
      ) : null}
    </section>
  );
}
