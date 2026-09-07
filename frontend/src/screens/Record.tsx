import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '../lib/api';
import { axisName, axisValues, itemByCode } from '../lib/catalog';
import { clearDraft, loadDraft, saveDraft, weeklyDraftKey } from '../lib/draft';
import { useMe, useSaveWeek, useTrajectory } from '../lib/queries';
import {
  axesFor, initialRecord, itemComplete, previousValue, steps, toWeeklyRequest,
  type RecordState,
} from '../lib/record';
import type { Catalog } from '../lib/types';
import { Button } from '../ui/Button';
import { Choice } from '../ui/Choice';
import { Notice } from '../ui/Notice';
import { Screen } from '../ui/Screen';

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

  // React Query의 isPending은 알림이 setTimeout(0)으로 미뤄져 다음 매크로태스크에나 참이 된다.
  // 버튼 disabled만으로는 빠른 두 번째 탭을 못 막는다. 동기 플래그로 잠근다.
  // 안드로이드 저가 기기의 유령 터치가 실제로 이 창을 때린다.
  // Hooks 규칙 때문에 아래의 조건부 return들보다 위에 있어야 한다 — 훅 호출 순서는
  // 렌더마다 같아야 한다(로딩 중/저장 완료로 일찍 return하는 렌더에서도 이 훅은 불려야 한다).
  const sending = useRef(false);

  const me = meQ.data;
  const draftKey = me ? weeklyDraftKey(me.caseId, me.week) : null;

  // 초안 불러오기. 주차가 바뀌면 키가 달라져 옛 초안은 자연히 보이지 않는다.
  useEffect(() => {
    if (!draftKey) return;
    const d = loadDraft<RecordState>(draftKey);
    if (d) setS(d);
  }, [draftKey]);

  useEffect(() => {
    if (draftKey && !saved) saveDraft(draftKey, s);
  }, [draftKey, s, saved]);

  if (!me) return <Screen><p>불러오는 중입니다…</p></Screen>;
  if (saved) {
    return (
      <Screen footer={<Button onClick={() => navigate('/', { replace: true })}>홈으로</Button>}>
        <p className="text-title font-semibold">기록을 남겼습니다.</p>
      </Screen>
    );
  }

  const flow = steps(me, catalog, s);
  const step = flow[Math.min(s.index, flow.length - 1)]!;
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
        <h2 className="text-title font-semibold">날짜가 바뀌었습니다</h2>
        <p className="pt-4">{weekMismatch}</p>
        <p className="pt-4">방금 적으신 내용은 어느 주의 것인가요?</p>
        <div className="flex flex-col gap-3 pt-8">
          <Button
            onClick={() => {
              sending.current = false;
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

  const common = { step: s.index + 1, total: flow.length, onBack: s.index > 0 ? () => go(-1) : undefined };
  const nextOrSave = (enabled: boolean) =>
    isLast
      ? <Button disabled={!enabled || save.isPending} onClick={submit}>
          {save.isPending ? '저장하는 중입니다…' : '저장하기'}
        </Button>
      : <Button disabled={!enabled} onClick={() => go(1)}>다음</Button>;

  const footer = (enabled: boolean) => (
    <div className="flex flex-col gap-3">
      {error ? <p>{error}</p> : null}
      {nextOrSave(enabled)}
    </div>
  );

  if (step.kind === 'ask') {
    return (
      <Screen {...common}>
        <h2 className="text-title font-semibold">지난주와 달라진 게 있나요?</h2>
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
        <h2 className="text-title font-semibold">
          이번 주는 {catalog.items.length}가지를 모두 여쭤봅니다
        </h2>
        <p className="pt-4 text-ink-soft">
          네 주에 한 번, 놓친 것이 없는지 처음부터 확인합니다. 지난번 답도 함께 보여드립니다.
        </p>
      </Screen>
    );
  }

  if (step.kind === 'pick') {
    return (
      <Screen {...common} footer={footer(s.selected.length > 0)}>
        <h2 className="text-title font-semibold">어떤 것이 달라졌나요?</h2>
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
        <h2 className="text-title font-semibold">{item.label}</h2>
        <p className="pt-4 text-ink-soft">요즘 어떠신가요?</p>

        {axes.map((axis) => {
          const prev = me.fullRecheck
            ? previousValue(trajQ.data ?? [], code, axis, me.week)
            : undefined;
          return (
            <div key={axis} className="pt-8">
              {axis === 'LEVEL' ? null : (
                <h3 className="font-semibold">{axisName(catalog, axis)}</h3>
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
            className="mt-2 min-h-[56px] w-full rounded-lg border border-line px-4"
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
        <h2 className="text-title font-semibold">이번 주에 불편해 보이신 적이 있나요?</h2>
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
              <h3 className="font-semibold">{a.label}</h3>
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
        <h2 className="text-title font-semibold">밤에 어떻게 주무셨나요?</h2>
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
      <h2 className="text-title font-semibold">그 밖에 남기고 싶으신 것</h2>
      <label className="block pt-6">
        <span className="text-small text-ink-soft">말씀하시듯 편하게 적어주세요</span>
        <textarea
          rows={6}
          className="mt-2 w-full rounded-lg border border-line p-4"
          value={s.freeNote.text}
          onChange={(e) => set({ freeNote: { ...s.freeNote, text: e.target.value } })}
        />
      </label>
      <Notice>키보드의 마이크를 누르면 말로 적을 수 있어요.</Notice>
      <div className="pt-8">
        <h3 className="font-semibold">주로 언제였나요? (안 고르셔도 됩니다)</h3>
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
