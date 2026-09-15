import { useState } from 'react';
import { Link } from 'react-router';
import { APP_NAME, WEEKLY_ICS_FILENAME } from '../lib/constants';
import { downloadIcs, weeklyReminderIcs } from '../lib/ics';
import { useMe, useReissueRecoveryCode, useUpdateVisitDate } from '../lib/queries';
import { loadRecoveryCode } from '../lib/recoveryCode';
import { Button } from '../ui/Button';
import { Notice } from '../ui/Notice';
import { TherapistLinkPanel } from '../ui/TherapistLinkPanel';

export function Settings() {
  const { data: me } = useMe();
  const updateVisit = useUpdateVisitDate();
  const reissue = useReissueRecoveryCode();
  const [visitDate, setVisitDate] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [codeShown, setCodeShown] = useState(false);
  // 서버는 해시만 갖고 있어 현재 코드를 되물을 수 없다. 이 기기에
  // 적어둔 것이 있을 때만 보여줄 수 있고, 없는 기기도 정상이다.
  const storedCode = loadRecoveryCode();

  if (!me) return <main className="mx-auto max-w-lg px-gutter py-10"><p>불러오는 중입니다…</p></main>;
  const dateValue = visitDate ?? me.nextVisitDate ?? '';

  return (
    <main className="mx-auto max-w-lg px-gutter py-10">
      <Link className="inline-flex min-h-[48px] items-center text-small text-ink-soft underline" to="/">
        ← 홈
      </Link>
      <h1 className="pt-6 text-title font-semibold">설정</h1>

      <section className="pt-12">
        <h2 className="font-semibold">다른 가족 초대하기</h2>
        {/*
          초대는 재발급이 아니다. POST /guardians/recover가 지금 있는 코드를 받는다.
          여기서 재발급하면 온보딩 때 적어두게 한 그 코드가 죽는다.
        */}
        <p className="pt-3">
          지금 갖고 계신 이어받기 코드를 알려주시면 됩니다. 받으신 분이 &lsquo;이어받기&rsquo;에서
          그 코드와 자신의 관계를 넣으면 같은 기록에 함께 남기실 수 있습니다.
        </p>
        <Notice>새 코드를 만들 필요가 없습니다. 누가 남긴 기록인지는 치료사용 요약에 함께 나갑니다.</Notice>

        {/* 안내는 "지금 갖고 계신 코드를 알려주시면 된다"고 말하는데 정작 그
            코드를 확인할 방법이 화면에 없었다. 서버가 되돌려줄 수 없는 값이라
            이 기기에 적어둔 것을 꺼내 보여준다. 상시 노출하지 않는 이유는
            어깨너머와 스크린샷이다 — 누를 때만 나온다. */}
        {storedCode ? (
          <div className="pt-4">
            {codeShown ? (
              <>
                <p className="py-4 text-center text-[34px] font-bold tracking-[0.2em]">{storedCode}</p>
                <button
                  type="button"
                  className="inline-flex min-h-[48px] items-center text-small text-ink-soft underline"
                  onClick={() => setCodeShown(false)}
                >
                  가리기
                </button>
              </>
            ) : (
              <Button className="btn btn-plain" onClick={() => setCodeShown(true)}>
                지금 코드 보기
              </Button>
            )}
          </div>
        ) : (
          <Notice>
            이 기기에는 코드가 저장돼 있지 않습니다. 적어두신 코드를 알려주시거나,
            찾지 못하셨다면 아래에서 새로 만드실 수 있습니다.
          </Notice>
        )}
      </section>

      <section className="pt-12">
        <h2 className="font-semibold">복구 코드 다시 만들기</h2>
        <p className="pt-3">적어두신 코드를 잃으셨을 때만 쓰세요.</p>

        {reissue.data ? (
          <div className="pt-4">
            <p className="py-6 text-center text-[34px] font-bold tracking-[0.2em]">
              {reissue.data.recoveryCode}
            </p>
            <p className="font-semibold">
              지금 적어두시거나 사진을 찍어두세요. 다시 보여드릴 수 없습니다.
            </p>
          </div>
        ) : confirming ? (
          <div className="pt-4">
            <p>지금 코드는 더 이상 쓸 수 없게 됩니다.</p>
            <p className="pt-2">그 코드로 초대하신 분도 새로 이어받으셔야 합니다.</p>
            <div className="flex flex-col gap-3 pt-6">
              <Button disabled={reissue.isPending} onClick={() => reissue.mutate()}>
                {reissue.isPending ? '만드는 중입니다…' : '네, 새로 만들겠습니다'}
              </Button>
              <Button variant="plain" onClick={() => setConfirming(false)}>그만두기</Button>
            </div>
            {reissue.isError ? <Notice>만들지 못했습니다. 잠시 후 다시 눌러주세요.</Notice> : null}
          </div>
        ) : (
          <div className="pt-4">
            <Button variant="plain" onClick={() => setConfirming(true)}>코드 새로 만들기</Button>
          </div>
        )}
      </section>

      <section className="pt-12">
        <h2 className="font-semibold">다음 진료일</h2>
        <label className="block pt-3">
          <span className="sr-only">다음 진료일</span>
          <input
            type="date"
            aria-label="다음 진료일"
            className="min-h-[56px] w-full rounded-lg border border-line px-4"
            value={dateValue}
            onChange={(e) => setVisitDate(e.target.value)}
          />
        </label>
        <div className="pt-4">
          <Button
            variant="plain"
            disabled={updateVisit.isPending}
            onClick={() => updateVisit.mutate(visitDate && visitDate.length > 0 ? visitDate : null)}
          >
            {updateVisit.isPending ? '저장하는 중입니다…' : '진료일 저장'}
          </Button>
        </div>
      </section>

      <section className="pt-12">
        <h2 className="font-semibold">주간 알림</h2>
        <p className="pt-3">쓰시는 달력에 매주 반복 일정을 넣어드립니다.</p>
        <div className="pt-4">
          <Button
            variant="plain"
            onClick={() =>
              downloadIcs(WEEKLY_ICS_FILENAME, weeklyReminderIcs({ startDate: me.today, appName: APP_NAME }))
            }
          >
            캘린더 파일 다시 받기
          </Button>
        </div>
      </section>

      <section className="pt-12">
        <h2 className="font-semibold">치료사 링크</h2>
        <div className="pt-4">
          <TherapistLinkPanel />
        </div>
      </section>
    </main>
  );
}
