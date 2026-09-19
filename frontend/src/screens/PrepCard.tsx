import { useState } from 'react';
import { AsyncState } from '../ui/AsyncState';
import { Button } from '../ui/Button';
import { Notice } from '../ui/Notice';
import { PageHeader } from '../ui/PageHeader';
import { TherapistLinkPanel } from '../ui/TherapistLinkPanel';
import { formatDate } from '../lib/format';
import { usePrepCard, useRegenerateQuestions } from '../lib/queries';
import { LegacyQuestions } from './prep/LegacyQuestions';
import { QuestionEditor } from './prep/QuestionEditor';
import { QuestionItemView } from './prep/QuestionItemView';
import { QuestionStatus } from './prep/QuestionStatus';

export function PrepCard() {
  const query = usePrepCard();
  const { data } = query;
  const regenerate = useRegenerateQuestions();
  const [editing, setEditing] = useState(false);
  const [pendingAtEdit, setPendingAtEdit] = useState(false);
  const [saved, setSaved] = useState(false);

  if (!data) {
    return (
      <main className="app-page">
        <PageHeader title="진료 준비" backTo="/" />
        <AsyncState
          kind={query.isError ? 'error' : 'loading'}
          message={query.isError ? '진료 질문을 불러오지 못했습니다.' : '불러오는 중입니다…'}
          onRetry={query.isError ? () => { void query.refetch(); } : undefined}
        />
      </main>
    );
  }

  const title = data.nextVisitDate ? `${formatDate(data.nextVisitDate)} 진료` : '진료 준비';
  const items = data.items;

  return (
    <main className="app-page space-y-8">
      <PageHeader backTo="/" title={title} focusKey="prep" />

      {items === undefined ? <LegacyQuestions card={data} /> : (
        <section aria-labelledby="visit-questions-title" className="space-y-5">
          <h2 id="visit-questions-title" className="text-[22px] font-semibold">
            진료실에서 여쭤볼 것
          </h2>
          <QuestionStatus
            card={data}
            editing={editing}
            regenerating={regenerate.isPending}
            regenerateFailed={regenerate.isError}
            onRegenerate={() => {
              setSaved(false);
              regenerate.mutate();
            }}
          />
          {editing ? (
            <>
              {pendingAtEdit && data.generationStatus !== 'PENDING' ? (
                <Notice role="status">
                  정리안이 준비됐어요. 취소하시면 정리안을 보여드려요.
                </Notice>
              ) : null}
              <QuestionEditor
                items={items}
                onCancel={() => setEditing(false)}
                onSaved={() => {
                  setEditing(false);
                  setSaved(true);
                }}
              />
            </>
          ) : (
            <>
              {items.length === 0 ? (
                data.emptyMessage ? <p>{data.emptyMessage}</p> : null
              ) : (
                <ol className="space-y-5">
                  {items.map((item, i) => (
                    <li key={item.id} className="min-w-0">
                      <QuestionItemView item={item} index={i} />
                    </li>
                  ))}
                </ol>
              )}
              <Button
                variant="plain"
                onClick={() => {
                  setSaved(false);
                  setPendingAtEdit(data.generationStatus === 'PENDING');
                  setEditing(true);
                }}
              >
                {items.length === 0 ? '+ 질문 적기' : '질문 고치기'}
              </Button>
              {saved ? <Notice role="status">질문을 저장했습니다.</Notice> : null}
            </>
          )}
        </section>
      )}

      {data.therapistGlance.length > 0 ? (
        <section className="note-surface">
          <h2 className="font-semibold">진료실에서 보여드릴 요약</h2>
          <ul className="flex flex-col gap-2 pt-3">
            {data.therapistGlance.map((glance) => (
              <li key={glance} className="text-ink-soft">{glance}</li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className="pt-10">
        <TherapistLinkPanel />
      </div>
    </main>
  );
}
