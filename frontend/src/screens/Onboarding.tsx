import { useEffect, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { ApiError, api, getToken, setToken } from '../lib/api';
import { axisName, axisValues } from '../lib/catalog';
import {
  APP_NAME, DIAGNOSIS_CHOICES, PARETIC_SIDE_CHOICES, RELATIONS, VERBAL_DIFFICULTY_CHOICES,
  WEEKLY_ICS_FILENAME,
} from '../lib/constants';
import { ONBOARDING_DRAFT, clearDraft, loadDraft, saveDraft } from '../lib/draft';
import { downloadIcs, weeklyReminderIcs } from '../lib/ics';
import {
  BASELINE_COUNT, FIRST_BASELINE_STEP, LAST_STEP, RECOVERY_STEP, SECOND_GUARDIAN_STEP,
  axesForStep, canAdvance, initialState, itemForStep, toOnboardingRequest, type OnboardingState,
} from '../lib/onboarding';
import type { Catalog, OnboardingResponse } from '../lib/types';
import { Button } from '../ui/Button';
import { Choice } from '../ui/Choice';
import { Notice } from '../ui/Notice';
import { Screen } from '../ui/Screen';

export function Onboarding({ catalog }: { catalog: Catalog }) {
  const [s, setS] = useState<OnboardingState>(
    () => loadDraft<OnboardingState>(ONBOARDING_DRAFT) ?? initialState(),
  );

  // 매 변화마다 남긴다. RECOVERY_STEP(복구 코드) 이후로는 케이스가 이미 만들어져 초안이 무의미하다.
  useEffect(() => {
    if (s.step < RECOVERY_STEP) saveDraft(ONBOARDING_DRAFT, s);
  }, [s]);

  const set = (patch: Partial<OnboardingState>) => setS((prev) => ({ ...prev, ...patch }));
  const go = (delta: number) => setS((prev) => ({ ...prev, step: prev.step + delta }));

  const navigate = useNavigate();
  const [recoveryCode, setRecoveryCode] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  // 토큰이 있으면 케이스가 이미 있다. 초안을 지우고 홈으로 보낸다.
  // 없으면 POST /cases를 두 번 보내 케이스를 둘 만들고 첫 번째를 버리게 된다.
  useEffect(() => {
    if (recoveryCode) return;          // 방금 만든 참이라 복구 코드 화면을 보여줘야 한다
    if (getToken() === null) return;
    clearDraft(ONBOARDING_DRAFT);
    navigate('/', { replace: true });
  }, [recoveryCode, navigate]);

  // create.isPending을 disabled 판단에 그대로 믿을 수 없다 — @tanstack/react-query 5.62는
  // notifyManager.schedule()을 실제 setTimeout(fn, 0)으로 미루므로, mutate() 직후
  // 매크로태스크 하나가 지나기 전까지는 이 렌더에 반영되지 않는다. 그 틈에 들어오는
  // 두 번째 클릭(대상 사용자의 저가 안드로이드 기기에서 흔한 중복·유령 터치 포함)을
  // 막으려면 ref로 동기 잠금을 걸어야 한다.
  const submitting = useRef(false);

  const create = useMutation({
    mutationFn: () => api.post<OnboardingResponse>('/cases', toOnboardingRequest(s)),
    onSuccess: (res) => {
      setToken(res.guardianToken);
      setRecoveryCode(res.recoveryCode);
      // 케이스가 생겼다. 초안은 이제 의미가 없다.
      clearDraft(ONBOARDING_DRAFT);
      setSaveError(null);
      setS((prev) => ({ ...prev, step: RECOVERY_STEP }));
      // RECOVERY_STEP으로 넘어가 이 버튼은 다시 보이지 않는다. 풀 필요가 없다.
    },
    onError: (e) => {
      submitting.current = false; // 재시도할 수 있어야 한다
      setSaveError(e instanceof ApiError ? e.message : '저장하지 못했습니다.');
    },
  });

  // 위 효과가 홈으로 보내는 동안, 이미 끝난 온보딩의 단계 화면이 한 프레임이라도 그려지면
  // 안 된다. 뒤로 가기로 재진입한 경우 특히 그렇다 — 답을 다시 바꿀 수 있는 것처럼 보인다.
  // recoveryCode가 있으면 방금 이 세션에서 만든 케이스이므로 막지 않는다.
  if (!recoveryCode && getToken() !== null) {
    return null;
  }

  const nextButton = (
    <Button disabled={!canAdvance(catalog, s)} onClick={() => go(1)}>다음</Button>
  );

  if (s.step === 0) {
    return (
      <Screen>
        <h1 className="text-title font-semibold">잠깐만 여쭤보겠습니다</h1>
        <p className="pt-4">5분 정도 걸립니다. 8가지를 여쭤봅니다.</p>
        <p className="pt-2 text-ink-soft">
          한 번만 하시면 됩니다. 그 뒤로는 매주 3분이면 충분합니다.
        </p>
        <div className="pt-8">
          <Button onClick={() => go(1)}>시작</Button>
        </div>
      </Screen>
    );
  }

  const common = { step: s.step, total: LAST_STEP, onBack: () => go(-1) };

  if (s.step === 1) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">어떤 분이신가요?</h2>
        <div className="flex flex-col gap-3 pt-6">
          {RELATIONS.map((r) => (
            <Choice
              key={r}
              label={r}
              selected={s.relation === r}
              onSelect={() => set({ relation: r })}
            />
          ))}
        </div>
        {s.relation === '기타' ? (
          <label className="block pt-4">
            <span className="text-small text-ink-soft">어떤 관계이신가요?</span>
            <input
              className="mt-2 min-h-[56px] w-full rounded-lg border border-line px-4 text-btn"
              value={s.relationOther}
              onChange={(e) => set({ relationOther: e.target.value })}
            />
          </label>
        ) : null}
      </Screen>
    );
  }

  if (s.step === 2) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">어떤 진단을 받으셨나요?</h2>
        <Notice>모르셔도 괜찮습니다. 나중에 바꿀 수 있습니다.</Notice>
        <div className="flex flex-col gap-3 pt-6">
          {DIAGNOSIS_CHOICES.map((c) => (
            <Choice
              key={c.value}
              label={c.label}
              selected={s.diagnosis === c.value}
              onSelect={() => set({ diagnosis: c.value })}
            />
          ))}
        </div>
      </Screen>
    );
  }

  if (s.step === 3) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">마비되신 쪽이 어디인가요?</h2>
        <div className="flex flex-col gap-3 pt-6">
          {PARETIC_SIDE_CHOICES.map((c) => (
            <Choice
              key={c.value}
              label={c.label}
              selected={s.pareticSide === c.value}
              onSelect={() => set({ pareticSide: c.value })}
            />
          ))}
        </div>
      </Screen>
    );
  }

  if (s.step === 4) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">말씀으로 불편한 곳을 알려주시나요?</h2>
        <div className="flex flex-col gap-3 pt-6">
          {VERBAL_DIFFICULTY_CHOICES.map((c) => (
            <Choice
              key={c.value}
              label={c.label}
              selected={s.verbalDifficulty === c.value}
              onSelect={() => set({ verbalDifficulty: c.value })}
            />
          ))}
        </div>
      </Screen>
    );
  }

  if (s.step === 5) {
    return (
      <Screen
        {...common}
        footer={
          <div className="flex flex-col gap-3">
            <Button disabled={!s.nextVisitDate} onClick={() => go(1)}>다음</Button>
            <Button variant="quiet" onClick={() => set({ nextVisitDate: null, step: s.step + 1 })}>
              건너뛰기
            </Button>
          </div>
        }
      >
        <h2 className="text-title font-semibold">다음 진료일이 정해져 있나요?</h2>
        <Notice>모르시면 건너뛰셔도 됩니다. 나중에 설정에서 넣으실 수 있습니다.</Notice>
        <input
          type="date"
          className="mt-6 min-h-[56px] w-full rounded-lg border border-line px-4 text-btn"
          value={s.nextVisitDate ?? ''}
          onChange={(e) => set({ nextVisitDate: e.target.value || null })}
        />
      </Screen>
    );
  }

  if (s.step === 6) {
    return (
      <Screen {...common} footer={<Button onClick={() => go(1)}>시작</Button>}>
        <h2 className="text-title font-semibold">지금 상태를 8가지로 한 번 여쭤보겠습니다.</h2>
        <p className="pt-4 text-ink-soft">
          지금 어떠신지가 기준이 됩니다. 정답이 없으니 보이시는 대로 고르시면 됩니다.
        </p>
      </Screen>
    );
  }

  const item = itemForStep(catalog, s.step);
  if (item) {
    const value = s.items[item.code] ?? { level: null, aid: null, consistency: null, hand: null, note: null };
    const axes = axesForStep(catalog, s, item);
    const setAxis = (axis: string, v: number) => {
      const key = axis.toLowerCase() as 'level' | 'aid' | 'consistency' | 'hand';
      set({ items: { ...s.items, [item.code]: { ...value, [key]: v } } });
    };
    const isLast = s.step === FIRST_BASELINE_STEP + BASELINE_COUNT - 1;

    return (
      <Screen
        {...common}
        footer={
          <div className="flex flex-col gap-3">
            {saveError ? <p className="text-ink">{saveError}</p> : null}
            <Button
              disabled={!canAdvance(catalog, s) || create.isPending}
              onClick={() => {
                setSaveError(null); // 재시도인 경우 지난 실패 문구를 남겨두지 않는다
                if (!isLast) {
                  go(1);
                  return;
                }
                // isPending은 아직 이 렌더에 반영되지 않았을 수 있다(위 주석 참고).
                // disabled만 믿지 않고 동기 ref로 한 번 더 막는다.
                if (submitting.current) return;
                submitting.current = true;
                create.mutate();
              }}
            >
              {create.isPending ? '저장하는 중입니다…' : '다음'}
            </Button>
          </div>
        }
      >
        <h2 className="text-title font-semibold">{item.label}</h2>
        <p className="pt-4 text-ink-soft">요즘 어떠신가요?</p>

        <div className="flex flex-col gap-3 pt-4">
          {axisValues(catalog, 'LEVEL').map((v) => (
            <Choice
              key={v.value}
              label={v.label}
              selected={value.level === v.value}
              onSelect={() => setAxis('LEVEL', v.value)}
            />
          ))}
        </div>

        {/* 축은 도움 수준을 고른 뒤에 나타난다. 처음부터 다 보이면 화면이 무겁다. */}
        {value.level !== null
          ? axes.filter((a) => a !== 'LEVEL').map((axis) => (
              <div key={axis} className="pt-8">
                <h3 className="font-semibold">{axisName(catalog, axis)}</h3>
                <div className="flex flex-col gap-3 pt-3">
                  {axisValues(catalog, axis).map((v) => (
                    <Choice
                      key={v.value}
                      label={v.label}
                      selected={
                        (axis === 'AID' && value.aid === v.value) ||
                        (axis === 'CONSISTENCY' && value.consistency === v.value) ||
                        (axis === 'HAND' && value.hand === v.value)
                      }
                      onSelect={() => setAxis(axis, v.value)}
                    />
                  ))}
                </div>
              </div>
            ))
          : null}
      </Screen>
    );
  }

  if (s.step === RECOVERY_STEP && recoveryCode) {
    return (
      <Screen footer={<Button onClick={() => go(1)}>적어뒀습니다</Button>}>
        <h2 className="text-title font-semibold">이어받기 코드</h2>
        <p className="py-8 text-center text-[34px] font-bold tracking-[0.2em]">{recoveryCode}</p>
        <p>폰을 바꾸거나 앱을 지우면 이 코드로 기록을 되찾습니다.</p>
        <p className="pt-4 font-semibold">
          지금 적어두시거나 사진을 찍어두세요. 다시 보여드릴 수 없습니다.
        </p>
        <Notice>저희는 이 코드를 그대로 갖고 있지 않아 다시 알려드릴 방법이 없습니다.</Notice>
      </Screen>
    );
  }

  if (s.step === SECOND_GUARDIAN_STEP) {
    return (
      <Screen
        footer={
          <div className="flex flex-col gap-3">
            <Button onClick={() => go(1)}>알겠습니다</Button>
          </div>
        }
      >
        <h2 className="text-title font-semibold">다른 가족도 함께 기록하시겠어요?</h2>
        <p className="pt-4">
          방금 그 코드를 알려주시면 됩니다. 받으신 분이 &lsquo;이어받기&rsquo;에서 코드를 넣으면
          같은 기록에 함께 남기실 수 있습니다.
        </p>
        <Notice>
          나중에 설정에서 다시 하실 수 있습니다. 누가 남긴 기록인지는 치료사용 요약에 함께 나갑니다.
        </Notice>
      </Screen>
    );
  }

  if (s.step === LAST_STEP) {
    return (
      <Screen
        footer={
          <div className="flex flex-col gap-3">
            <Button
              onClick={() => {
                downloadIcs(
                  WEEKLY_ICS_FILENAME,
                  weeklyReminderIcs({ startDate: todayIso(), appName: APP_NAME }),
                );
                navigate('/', { replace: true });
              }}
            >
              캘린더에 넣기
            </Button>
            <Button variant="quiet" onClick={() => navigate('/', { replace: true })}>
              나중에 하기
            </Button>
          </div>
        }
      >
        <h2 className="text-title font-semibold">매주 알림을 받으시겠어요?</h2>
        <p className="pt-4">
          쓰시는 달력에 매주 같은 요일로 반복 일정을 넣어드립니다. 알림은 달력이 울립니다.
        </p>
        <Notice>설정에서 언제든 다시 받으실 수 있습니다.</Notice>
      </Screen>
    );
  }

  // 새로고침 등으로 복구 코드를 잃은 채 15단계에 온 경우. 이미 토큰은 있으므로 홈으로 보낸다.
  return (
    <Screen footer={<Button onClick={() => navigate('/', { replace: true })}>홈으로</Button>}>
      <p>준비가 끝났습니다.</p>
    </Screen>
  );
}

function todayIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
