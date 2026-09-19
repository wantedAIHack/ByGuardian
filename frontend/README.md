# 프론트엔드 — React PWA

`frontend/`는 보호자가 쓰는 React 19·Vite PWA입니다. 홈에서 온보딩·이어받기·주간 기록으로
들어가고, 전체 궤적·진료 준비 카드·설정을 읽습니다. 치료사용 요약은 보호자용 껍데기 밖의 읽기 전용
화면으로 분리되어 있습니다.

## 실행

```text
Runtime: Node 22.22.2
Test baseline (2026-09-10): FE 205 tests pass
Install: npm ci
Unit: npm test
Types: npm run typecheck
Build: npm run build
Local API: VITE_API_BASE=http://localhost:8080
Production API: VITE_API_BASE is the owner-approved https://api subdomain
```

`VITE_API_BASE`의 운영 호스트명은 보호된 배포 설정으로 공급합니다. 이 값은 공개 API 주소일 뿐
비밀값이 아니므로 저장소에 비밀값처럼 넣거나 공유할 필요는 없으며, 실제 도메인은 소유자가 승인합니다.

현재 소스 라우트는 `/`, `/onboarding`, `/record`, `/trajectory`, `/prep-card`, `/settings`,
`/recover`, `/demo`, `/demo/onboarding`, 그리고 치료사용 요약 `/t/:token`입니다. `/demo`는 일반
온보딩을 그대로 사용하되 데모 토큰을 현재 탭에만 보관하고, 기록 뒤 가상 날짜를 일주일씩 진행합니다.
치료사용 공개 링크의 운영 보안 전환은
프론트엔드 설계의 2026-09-10 production security amendment를 따라 외부 배포 전에 적용합니다.

## 기준 문서

- [제품과 실제 통합 상태](../README.md)
- [프론트엔드 설계](../docs/superpowers/specs/2026-09-06-frontend-design.md)
- [API 설계](../docs/superpowers/specs/2026-09-05-api-design.md)
- [단일 호스트 배포 설계](../docs/superpowers/specs/2026-09-17-single-host-deployment-design.md)
