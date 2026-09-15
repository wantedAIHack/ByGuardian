import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, setTokenChangeHandler } from './api';
import { saveRecoveryCode } from './recoveryCode';
import type {
  Me, PrepCard, Progress, RecoveryCodeResponse, TherapistLink, Trajectory,
  WeeklyRecordRequest, WeeklyRecordResponse,
} from './types';

export const QK = {
  me: ['me'] as const,
  progress: ['me', 'progress'] as const,
  trajectory: ['me', 'trajectory'] as const,
  prepCard: ['me', 'prep-card'] as const,
  therapistLink: ['me', 'therapist-link'] as const,
};

/**
 * 읽기 훅은 전부 enabled를 받는다. 홈은 토큰이 없거나 이번 주 기록이 없을 때
 * /me/progress를 부를 이유가 없고, 부르면 401이나 빈 응답을 받으려고 왕복만 한다.
 * 기본값은 true라 호출부가 조건이 없으면 그냥 부른다.
 */
export const useMe = (enabled = true) =>
  useQuery({ queryKey: QK.me, queryFn: () => api.get<Me>('/me'), enabled });

export const useProgress = (enabled = true) =>
  useQuery({ queryKey: QK.progress, queryFn: () => api.get<Progress>('/me/progress'), enabled });

export const useTrajectory = (enabled = true) =>
  useQuery({ queryKey: QK.trajectory, queryFn: () => api.get<Trajectory[]>('/me/trajectory'), enabled });

export const usePrepCard = (enabled = true) =>
  useQuery({ queryKey: QK.prepCard, queryFn: () => api.get<PrepCard>('/me/prep-card'), enabled });

/** 저장이 끝나면 홈·경과·궤적·준비 카드가 전부 낡는다. 통째로 무효화한다. */
export function useSaveWeek() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { week: number; body: WeeklyRecordRequest }) =>
      api.put<WeeklyRecordResponse>(`/me/weeks/${v.week}`, v.body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

export function useSaveExtra() {
  const qc = useQueryClient();
  return useMutation({
    // API 전체에서 유일하게 객체가 아니라 배열을 돌려준다
    mutationFn: (questions: string[]) =>
      api.put<string[]>('/me/prep-card/extra', { questions }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: QK.prepCard });
    },
  });
}

/**
 * 발급된 링크를 컴포넌트 로컬 뮤테이션 상태가 아니라 공유 쿼리 캐시(QK.therapistLink)에
 * 둔다. TherapistLinkPanel은 홈·준비 카드·설정 세 화면에 각각 따로 마운트되는데, 링크를
 * 뮤테이션 로컬 상태로 두면 인스턴스마다 "발급 전" 화면부터 다시 시작한다 — 재발급은
 * 이전 토큰을 그 자리에서 죽이므로(TherapistControllerTest.reissueRevokesPreviousLink),
 * 다른 화면에서 이미 보호자가 치료사에게 전달한 주소가 있는 줄 모르고 또 하나를 만들면
 * 방금 전달한 주소가 죽는다. GET으로 "지금 발급된 링크"를 조회하는 엔드포인트는 없다
 * (POST뿐이다) — 그래서 이 쿼리는 스스로 fetch하지 않고, 아래 뮤테이션의 onSuccess가
 * 캐시를 채우는 유일한 통로다. setQueryData로 쓰면 이미 마운트된 다른 패널의 구독에도
 * 그대로 퍼진다 — 그래서 세 화면이 늘 같은 링크를 보여준다.
 *
 * data는 cached.data만 본다 — mutation.data로 대체 응답하지 않는다. 뮤테이션 자신의
 * data는 clearTherapistLinkOnTokenChange가 쿼리 캐시를 지워도 따라 지워지지 않는(뮤테이션과
 * 쿼리는 서로 다른 캐시다) 두 번째 출처라, 남겨두면 토큰이 바뀐 뒤에도 같은 컴포넌트
 * 인스턴스가 살아있는 한 옛 링크를 계속 돌려줄 길이 남는다. cached.data는 onSuccess가
 * 같은 렌더 배치 안에서 채워주므로 이 대체는 애초에 필요하지도 않았다.
 */
export function useIssueLink() {
  const qc = useQueryClient();
  const cached = useQuery<TherapistLink | null>({
    queryKey: QK.therapistLink,
    queryFn: () => null,
    enabled: false,
    gcTime: Infinity,
  });
  const mutation = useMutation({
    mutationFn: () => api.post<TherapistLink>('/me/therapist-link'),
    onSuccess: (data) => {
      qc.setQueryData(QK.therapistLink, data);
    },
  });
  return { ...mutation, data: cached.data ?? null };
}

/**
 * 토큰이 바뀌면(온보딩·이어받기·데모·401 처리 — setToken/clearToken을 부르는 모든 곳)
 * 발급된 치료사 링크 캐시를 지운다. 안 지우면 이전 케이스의 링크가 새 보호자에게 그대로
 * 보이고, 캐시에 값이 남아 있다는 이유만으로 TherapistLinkPanel이 발급 버튼 자체를
 * 숨겨버려 새 보호자가 자기 링크를 낼 방법이 없어진다. main.tsx가 QueryClient를 만든
 * 직후 한 번 불러 등록한다 — setToken/clearToken 호출부 중 어디에도 따로 기억해 둘 게 없다.
 */
export function clearTherapistLinkOnTokenChange(qc: QueryClient): void {
  setTokenChangeHandler(() => {
    qc.removeQueries({ queryKey: QK.therapistLink });
  });
}

export function useUpdateVisitDate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (nextVisitDate: string | null) => api.patch<Me>('/me', { nextVisitDate }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

export const useReissueRecoveryCode = () =>
  useMutation({
    mutationFn: () => api.post<RecoveryCodeResponse>('/me/recovery-code'),
    // 새 코드도 서버는 해시만 남긴다. 여기서 적어두지 않으면 이 화면을
    // 벗어나는 순간 다시는 못 본다.
    onSuccess: (res) => saveRecoveryCode(res.recoveryCode),
  });
