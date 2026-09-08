# 배포 전 수동 확인 목록

이 샌드박스에는 브라우저가 없고, `bootRun`도 여기서는 못 돈다(H2가 `testRuntimeOnly`라
Postgres가 필요하고, 도커 이미지 받기가 멈춘다). 그래서 여기서는 못 돌리고, 배포 전에
사람이 실제 브라우저에서 아래를 훑는다.

```bash
# 백엔드
cd backend && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :api:bootRun
# 프론트 (다른 터미널)
cd frontend && export PATH="$HOME/.nvm/versions/node/v22.22.2/bin:$PATH" && npm run dev
```

1. `/demo` → "데모 기록 만들기" → 보호자 화면으로 이동
2. 홈이 침묵 또는 변화 상태로 뜨고, 조용한 목록에 "N주 중 M주 기록"이 보이는지
3. "전체 기록 보기" → 궤적에 옅은 값과 범례가 보이는지
4. 홈에서 "치료사에게 보여드리기" → 주소 발급 → 새 탭에서 표·수면·원문·한계 문단이 순서대로 나오는지
5. 인쇄 미리보기(⌘P)에서 표가 잘리지 않는지
6. `localStorage.clear()` 뒤 `/onboarding`을 끝까지 → 복구 코드가 나오는지
7. 그 코드로 다른 브라우저 프로필에서 `/recover` → 관계를 고르고 이어받아지는지
8. 홈에서 "3분 기록하기" → "없어요" → 수면 → 저장 → 홈이 침묵으로 바뀌는지
9. 콘솔에 오류가 없는지
