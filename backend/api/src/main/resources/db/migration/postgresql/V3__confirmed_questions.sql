-- 2026-09-17 정리 설계 4.1: 보호자가 확정한 진료 질문 목록
alter table cases add column confirmed_questions jsonb;
alter table cases add column confirmed_at timestamp with time zone;
