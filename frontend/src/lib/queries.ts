import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './api';
import type {
  Me, PrepCard, Progress, RecoveryCodeResponse, TherapistLink, Trajectory,
  WeeklyRecordRequest, WeeklyRecordResponse,
} from './types';

export const QK = {
  me: ['me'] as const,
  progress: ['me', 'progress'] as const,
  trajectory: ['me', 'trajectory'] as const,
  prepCard: ['me', 'prep-card'] as const,
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

export const useIssueLink = () =>
  useMutation({ mutationFn: () => api.post<TherapistLink>('/me/therapist-link') });

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
  useMutation({ mutationFn: () => api.post<RecoveryCodeResponse>('/me/recovery-code') });
