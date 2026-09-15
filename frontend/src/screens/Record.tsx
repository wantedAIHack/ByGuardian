import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '../lib/api';
import { axisQuestion, axisValues, itemByCode } from '../lib/catalog';
import { DICTATION_HINT_SEEN, clearDraft, loadDraft, saveDraft, weeklyDraftKey } from '../lib/draft';
import { useMe, useSaveWeek, useTrajectory } from '../lib/queries';
import {
  axesFor, initialRecord, itemComplete, previousValue, steps, toWeeklyRequest,
  type RecordState, type Step,
} from '../lib/record';
import type { Catalog } from '../lib/types';
import { Button } from '../ui/Button';
import { Choice } from '../ui/Choice';
import { Notice } from '../ui/Notice';
import { Screen } from '../ui/Screen';
import { AsyncState } from '../ui/AsyncState';

export function Record({ catalog }: { catalog: Catalog }) {
  const navigate = useNavigate();
  const meQ = useMe();
  // 지난달 답은 전체 재확인 주에만 보여준다. 보통 주에는 부르지 않는다.
  const trajQ = useTrajectory(meQ.data?.fullRecheck === true);
  const save = useSaveWeek();

  const [s, setS] = useState<RecordState>(initialRecord);
  const [error, setError] = useState<string | null>(null);
  const [weekMismatch, setWeekMismatch] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // 마이크 안내를 보여줄지는 마운트 시점에 딱 한 번 정해서 이 방문 내내 고정한다.
  // 매 렌더 loadDraft를 다시 읽으면, 안내가 뜬 바로 그 방문에서 아래 효과가 플래그를
  // 쓰자마자(글자 하나만 입력해도 재렌더된다) 안내가 문장 중간에 사라진다.
  const [hintAlreadySeen] = useState(() => loadDraft<boolean>(DICTATION_HINT_SEEN) === true);

  // mutate()는 뮤테이션 옵저버를 그 자리에서 동기적으로 pending으로 바꾼다 — 하지만
  // 구독자에게 그 사실을 알리는 notifyManager 알림은 setTimeout(0)으로 미뤄진다. 즉
  // save.isPending은 "다음에 어떤 이유로든 렌더가 일어나면" 그때 반영되는 값이지,
  // mutate() 호출 자체가 렌더를 부르지는 않는다. 렌더가 그 사이에 한 번도 없으면
  // disabled={save.isPending}은 낡은 값(false)을 계속 들고 있어, 두 번째 클릭이 그대로
  // 통과한다.
  // 이 화면에서는 마침 그 렌더가 우연히 일어난다 — 바로 위 setError(null)이 같은
  // 이벤트 핸들러 안에서 상태 갱신을 하나 걸어 두고, React가 이를 mutate() 호출 *이후*
  // 한 커밋으로 묶어 처리하면서 save.isPending의 최신값을 함께 읽어 온다. 그 결과
  // disabled가 같은 클릭 처리 안에서 이미 true로 반영되는 것처럼 보인다. 하지만 이건
  // 이 파일의 구조가 아니라 "error를 지우는 문장이 mutate() 호출보다 앞줄에 있다"는
  // 우연이다 — 온보딩(Task 5)의 setSaveError(null)은 같은 자리(mutate 호출 앞)에서
  // 같은 일을 하는 것처럼 보이지만 여기서는 두 번째 렌더를 부르지 못했고(이미 null인
  // 값을 다시 null로 지우는 게 React의 얕은 비교에 걸려 아무 것도 스케줄되지 않았다),
  // 그래서 세 번 클릭에 세 번의 POST가 나갔다 — 이 화면에서 같은 실험을 했을 때는 잠금을
  // 지워도 한 번만 나갔다. 구조적 차이가 아니라 statement 순서의 사고다.
  // 그래서 이 동기 ref는 "거의 공짜인 두 번째 방어선"이 아니라, disabled 하나만으로는
  // 보장되지 않는 유일한 방어선이다 — 위 setError(null)이 자리를 옮기거나(예: onMutate로
  // 이동) 지워지면 이 우연한 렌더도 함께 사라지고 중복 제출이 조용히 되돌아온다. ref는
  // mutate()가 실제로 커밋한 순간(동기)에 바로 잠기므로 그 렌더 타이밍에 기대지 않는다 —
  // 안드로이드 저가 기기의 유령 터치처럼 렌더와 무관하게 들어오는 중복 이벤트에도 그대로
  // 유효하다(테스트 참고: '중복 제출 방지' — 잠금을 지우면 두 번째 클릭도 실제로 POST를 낸다).
  // 아래 onError의 `sending.current = false` 리셋은 이야기가 다르다 — 이건 중복 제출
  // 방지가 아니라 순수하게 재시도를 위한 것이고, 지우면 첫 실패 뒤 저장 버튼이 다시는
  // 동작하지 않는다(직접 확인함 — 이번 라운드의 '저장에 실패한 뒤 다시 누르면 성공한다').
  // Hooks 규칙 때문에 아래의 조건부 return들보다 위에 있어야 한다 — 훅 호출 순서는
  // 렌더마다 같아야 한다(로딩 중/저장 완료로 일찍 return하는 렌더에서도 이 훅은 불려야 한다).
  const sending = useRef(false);

  const me = meQ.data;
  const draftKey = me ? weeklyDraftKey(me.caseId, me.week) : null;

  // 화면 차례는 me가 있어야 계산할 수 있다. 아직 없으면 빈 배열로 둔다 — 훅 규칙 때문에
  // 아래 조건부 return보다 앞에서, 매 렌더 같은 순서로 계산해 둬야 한다(마이크 안내를
  // 한 번만 보여주는 효과가 지금이 몇 번째 단계인지 알아야 하기 때문).
  const flow: Step[] = me ? steps(me, catalog, s) : [];
  const step: Step | undefined = flow[Math.min(s.index, Math.max(flow.length - 1, 0))];

  // 초안 불러오기. 주차가 바뀌면 키가 달라져 옛 초안은 자연히 보이지 않는다.
  useEffect(() => {
    if (!draftKey) return;
    const d = loadDraft<RecordState>(draftKey);
    if (d) setS(d);
  }, [draftKey]);

  useEffect(() => {
    if (draftKey && !saved) saveDraft(draftKey, s);
  }, [draftKey, s, saved]);

  // 마이크 안내는 첫 사용 때 한 번만(설계서 §5: "자유 기록 칸은... 첫 사용 때 한 번만").
  // 이번 방문에 보여줄지는 위의 hintAlreadySeen(마운트 시점 고정값)이 이미 정했다 — 여기서는
  // "다음 주부터는 안 보이게" 표시만 남긴다. 지금 렌더의 판단에는 관여하지 않는다.
  // 읽기·쓰기 모두 draft.ts의 방어적 try/catch를 그대로 쓴다 — 실패해도 화면은 안 죽는다.
  useEffect(() => {
    if (step?.kind === 'note' && !hintAlreadySeen) {
      saveDraft(DICTATION_HINT_SEEN, true);
    }
  }, [step?.kind, hintAlreadySeen]);

  if (!me) return <Screen><AsyncState kind={meQ.isError ? 'error' : 'loading'}
    message={meQ.isError ? '기록 정보를 불러오지 못했습니다.' : '불러오는 중입니다…'}
    onRetry={meQ.isError ? () => { void meQ.refetch(); } : undefined} /></Screen>;
  if (saved) {
    return (
      <Screen stageLabel="기록 완료" focusKey="saved" footer={<Button onClick={() => navigate('/', { replace: true })}>홈으로</Button>}>
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">기록을 남겼습니다.</h1>
      </Screen>
    );
  }
  if (!step) return <Screen><p>불러오는 중입니다…</p></Screen>; // me가 있으면 항상 있다 — 타입만 좁힌다.

  const isLast = s.index >= flow.length - 1;
  const set = (patch: Partial<RecordState>) => setS((p) => ({ ...p, ...patch }));
  const go = (d: number) => setS((p) => ({ ...p, index: Math.max(0, p.index + d) }));

  const submit = () => {
    if (sending.current) return;
    sending.current = true;
    setError(null);
    save.mutate(
      { week: me.week, body: toWeeklyRequest(me, catalog, s) },
      {
        onSuccess: () => {
          if (draftKey) clearDraft(draftKey);
          setSaved(true);
        },
        onError: (e) => {
          sending.current = false; // 다시 시도할 수 있어야 한다
          if (e instanceof ApiError && e.code === 'WEEK_MISMATCH') {
            // 그대로 재시도하면 지난주에 본 것이 이번 주 기록이 된다. 물어본다.
            setWeekMismatch(e.message);
            void meQ.refetch();
            return;
          }
          setError(e instanceof ApiError ? e.message : '저장하지 못했습니다.');
        },
      },
    );
  };

  if (weekMismatch) {
    return (
      <Screen>
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">날짜가 바뀌었습니다</h1>
        <p className="pt-4">{weekMismatch}</p>
        <p className="pt-4">방금 적으신 내용은 어느 주의 것인가요?</p>
        <div className="flex flex-col gap-3 pt-8">
          {/* 둘 다 refetch가 끝날 때까지 잠근다. 자정 무렵의 새 /me 응답이 아직 안 왔는데
              먼저 눌리면, 이번 주가 실은 전체 재확인 주인데도 옛 me.fullRecheck로 판단해
              부분 흐름을 열게 된다 — 그 갱신 자체는 스스로 고쳐지지만, 보호자는 약속받은
              재확인 흐름 대신 도중에 놓인다. sending의 동기 잠금과 같은 이유, 다른 자원이다. */}
          <Button
            disabled={meQ.isFetching}
            onClick={() => {
              setWeekMismatch(null);
              // 새 주차가 전체 재확인 주면 부분 기록으로는 저장할 수 없다. 흐름을 처음부터 연다.
              if (meQ.data?.fullRecheck) {
                setS({ ...initialRecord() });
              }
              setError(null);
            }}
          >
            이번 주 것입니다
          </Button>
          <Button
            variant="plain"
            disabled={meQ.isFetching}
            onClick={() => {
              if (draftKey) clearDraft(draftKey);
              navigate('/', { replace: true });
            }}
          >
            지난주 것입니다
          </Button>
        </div>
        <Notice>
          지난주 것이라면 그 주는 이미 닫혀 있어 남길 수 없습니다. 다음 주 기록에서 이어가시면 됩니다.
        </Notice>
      </Screen>
    );
  }

  const common = { stageLabel: '이번 주 관찰', focusKey: s.index, step: s.index + 1, total: flow.length, onBack: s.index > 0 ? () => go(-1) : undefined };
  const nextOrSave = (enabled: boolean) =>
    isLast
      ? <Button disabled={!enabled || save.isPending} onClick={submit}>
          {save.isPending ? '저장하는 중입니다…' : '저장하기'}
        </Button>
      : <Button disabled={!enabled} onClick={() => go(1)}>다음</Button>;

  const footer = (enabled: boolean) => (
    <div className="flex flex-col gap-3">
      {error ? <p role="alert">{error}</p> : null}
      {nextOrSave(enabled)}
    </div>
  );

  if (step.kind === 'ask') {
    return (
      <Screen {...common}>
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">지난주와 달라진 게 있나요?</h1>
        <div className="flex flex-col gap-4 pt-10">
          <Button onClick={() => setS((p) => ({ ...p, noChange: false, index: p.index + 1 }))}>
            네, 달라진 게 있어요
          </Button>
          <Button
            variant="plain"
            onClick={() => setS((p) => ({ ...p, noChange: true, selected: [], index: p.index + 1 }))}
          >
            없어요
          </Button>
        </div>
      </Screen>
    );
  }

  if (step.kind === 'recheck-intro') {
    return (
      <Screen {...common} footer={<Button onClick={() => go(1)}>시작</Button>}>
        {/* 개수를 상수로 박지 않는다. README §11이 8항목을 5개로 줄이는 것을 검토 중이다. */}
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">
          이번 주는 {catalog.items.length}가지를 모두 여쭤봅니다
        </h1>
        <p className="pt-4 text-ink-soft">
          네 주에 한 번, 놓친 것이 없는지 처음부터 확인합니다. 지난번 답도 함께 보여드립니다.
        </p>
      </Screen>
    );
  }

  if (step.kind === 'pick') {
    return (
      <Screen {...common} footer={footer(s.selected.length > 0)}>
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">어떤 것이 달라졌나요?</h1>
        <Notice>여러 개를 고르셔도 됩니다.</Notice>
        <div className="flex flex-col gap-3 pt-6">
          {catalog.items.map((item) => (
            <Choice
              key={item.code}
              label={item.label}
              selected={s.selected.includes(item.code)}
              onSelect={() =>
                set({
                  selected: s.selected.includes(item.code)
                    ? s.selected.filter((x) => x !== item.code)
                    : [...s.selected, item.code],
                })
              }
            />
          ))}
        </div>
      </Screen>
    );
  }

  if (step.kind === 'item') {
    const code = step.code;
    const item = itemByCode(catalog, code)!;
    const v = s.items[code] ?? { level: null, aid: null, consistency: null, hand: null, note: null };
    const axes = axesFor(me, catalog, code);
    const setAxis = (axis: string, value: number) => {
      const key = axis.toLowerCase() as 'level' | 'aid' | 'consistency' | 'hand';
      set({ items: { ...s.items, [code]: { ...v, [key]: value } } });
    };

    return (
      <Screen {...common} footer={footer(itemComplete(me, catalog, s, code))}>
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">{item.label}</h1>
        <p className="pt-4 text-ink-soft">요즘 어떠신가요?</p>

        {axes.map((axis) => {
          const prev = me.fullRecheck
            ? previousValue(trajQ.data ?? [], code, axis, me.week)
            : undefined;
          return (
            <div key={axis} className="pt-8">
              {axis === 'LEVEL' ? null : (
                <h2 className="font-semibold">{axisQuestion(catalog, axis)}</h2>
              )}
              {prev ? <Notice>지난번에는 {prev.label}</Notice> : null}
              <div className="flex flex-col gap-3 pt-3">
                {axisValues(catalog, axis).map((val) => (
                  <Choice
                    key={val.value}
                    label={val.label}
                    selected={
                      (axis === 'LEVEL' && v.level === val.value) ||
                      (axis === 'AID' && v.aid === val.value) ||
                      (axis === 'CONSISTENCY' && v.consistency === val.value) ||
                      (axis === 'HAND' && v.hand === val.value)
                    }
                    onSelect={() => setAxis(axis, val.value)}
                  />
                ))}
              </div>
            </div>
          );
        })}

        <label className="block pt-8">
          <span className="text-small text-ink-soft">한 줄 적어두실 것이 있나요? (안 적으셔도 됩니다)</span>
          <input
            className="mt-2 min-h-[56px] w-full rounded-lg border border-control bg-paper px-4"
            value={v.note ?? ''}
            onChange={(e) => set({ items: { ...s.items, [code]: { ...v, note: e.target.value } } })}
          />
        </label>
      </Screen>
    );
  }

  if (step.kind === 'signal') {
    const chosen = s.painSignal ?? {};
    const toggle = (action: string, kind: string) => {
      const cur = chosen[action] ?? [];
      const next = cur.includes(kind) ? cur.filter((k) => k !== kind) : [...cur, kind];
      const copy = { ...chosen };
      if (next.length === 0) delete copy[action];
      else copy[action] = next;
      set({ painSignal: copy });
    };

    return (
      <Screen {...common} footer={footer(true)}>
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">이번 주에 불편해 보이신 적이 있나요?</h1>
        <Notice>말씀으로 표현이 어려우실 때, 표정이나 몸짓에서 보이는 것들입니다.</Notice>
        <div className="pt-6">
          <Button
            variant={Object.keys(chosen).length === 0 ? 'primary' : 'plain'}
            onClick={() => setS((p) => ({ ...p, painSignal: {}, index: p.index + 1 }))}
          >
            없었어요
          </Button>
        </div>
        <div className="pt-10">
          {catalog.signalActions.map((a) => (
            <div key={a.code} className="pt-6">
              <h2 className="font-semibold">{a.label}</h2>
              <div className="flex flex-col gap-3 pt-3">
                {catalog.signalKinds.map((k) => (
                  <Choice
                    key={k.code}
                    label={k.label}
                    selected={(chosen[a.code] ?? []).includes(k.code)}
                    onSelect={() => toggle(a.code, k.code)}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      </Screen>
    );
  }

  if (step.kind === 'sleep') {
    return (
      <Screen {...common} footer={footer(s.sleep !== null)}>
        <h1 data-step-title tabIndex={-1} className="text-title font-semibold">밤에 어떻게 주무셨나요?</h1>
        <Notice>이번 주 대체로 어떠셨는지로 골라주세요.</Notice>
        <div className="flex flex-col gap-3 pt-6">
          {catalog.sleepLevels.map((l) => (
            <Choice
              key={l.code}
              label={l.label}
              selected={s.sleep === Number(l.code)}
              onSelect={() => set({ sleep: Number(l.code) })}
            />
          ))}
        </div>
      </Screen>
    );
  }

  return (
    <Screen {...common} footer={footer(true)}>
      <h1 data-step-title tabIndex={-1} className="text-title font-semibold">그 밖에 남기고 싶으신 것</h1>
      <label className="block pt-6">
        <span className="text-small text-ink-soft">말씀하시듯 편하게 적어주세요</span>
        <textarea
          rows={6}
          className="mt-2 w-full rounded-lg border border-control bg-paper p-4"
          value={s.freeNote.text}
          onChange={(e) => set({ freeNote: { ...s.freeNote, text: e.target.value } })}
        />
      </label>
      {/* 첫 사용 때 한 번만. 마운트 시점에 고정한 값이라 이 방문 중에는 입력해도 사라지지 않는다. */}
      {hintAlreadySeen ? null : (
        <Notice>키보드의 마이크를 누르면 말로 적을 수 있어요.</Notice>
      )}
      <div className="pt-8">
        <h2 className="font-semibold">주로 언제였나요? (안 고르셔도 됩니다)</h2>
        <div className="flex flex-col gap-3 pt-3">
          {catalog.timeTags.map((t) => (
            <Choice
              key={t.code}
              label={t.label}
              selected={s.freeNote.timeTag === t.code}
              onSelect={() =>
                set({
                  freeNote: {
                    ...s.freeNote,
                    timeTag: s.freeNote.timeTag === t.code ? null : t.code,
                  },
                })
              }
            />
          ))}
        </div>
      </div>
    </Screen>
  );
}
