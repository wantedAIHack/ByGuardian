# 단일 호스트 배포 설계

작성일 2026-09-17. [2026-09-10 전체 서비스 통합·운영 배포 설계](2026-09-10-full-service-deployment-design.md)는
AWS EC2/ECR/SSM/S3/Caddy와 Cloudflare Access 뒤의 `llm.<도메인>`을 전제로 승인됐지만, 그 구조는
실제로 구축되지 않았다. 실제 운영은 Ubuntu 노트북 한 대(SSH alias `llm`, 사용자 `milo`)에서 API,
PostgreSQL, Ollama, cloudflared가 모두 함께 돌아가는 형태로 이뤄지고 있다. 이 문서는 그 실물을
기준으로 다시 쓴 배포 설계이며, 사실 근거는 2026-09-17 실사 문서
[`docs/qa/2026-09-17-single-host-inventory.md`](../../qa/2026-09-17-single-host-inventory.md)다.

이 문서는 제품 기능을 다시 설계하지 않는다. 건강 점수·진단·운동 처방을 만들지 않고 색상이 환자의
호전·악화·위험을 나타내지 않는다는 규칙은 2026-09-10 설계서와 동일하게 유지된다. 이 문서는 배포
경계와 완료 조건만 다룬다.

이 문서의 4절 완료 조건은 앞으로 이어지는 단일 호스트 운영 완료 작업들(도메인 전환, 백업/복원 시험,
백엔드 재배포, LLM 수정, 최종 인수)이 그대로 인용하는 번호다. 번호는 이 문서에서 재부여하지 않는다.

---

## 1. 결정 요약

- 운영은 **단일 Ubuntu 노트북 한 대**에서 이뤄진다. API(Spring Boot), PostgreSQL, Ollama, cloudflared가
  모두 이 호스트 위에 있다. 여러 호스트로 나누지 않는다.
- 이 호스트에서 인터넷에 공개된 것은 **SSH(22)뿐**이다. API(8080)와 Ollama(11434)는 `127.0.0.1`에만
  바인딩되고, PostgreSQL은 호스트 포트 자체를 게시하지 않는다(실사: `ss -tlnp`,
  `docker ps -a --format "{{.Ports}}"`, 실사 문서 "포트 노출" 절).
- **AWS EC2, ECR, SSM, S3, Caddy는 쓰지 않는다.** 2026-09-10 설계서가 이 구성요소들의 구축을
  전제했지만 실제로 만들어진 적이 없고, 이 설계에서도 다시 만들지 않는다.
- **공개 `llm.<도메인>` 호스트명을 쓰지 않는다.** API가 Ollama를 호출하는 경로는 Docker 내부
  호출이다. API 컨테이너(`nextvisit-demo-api-1`)와 Ollama 컨테이너(`nextvisit-llm-ollama-1`)는
  `nextvisit-llm_default` 브리지 네트워크를 공유하며, API는 이 네트워크 안에서 Ollama 컨테이너의
  DNS alias(`ollama`)로 접근한다(확인 명령:
  `docker inspect nextvisit-demo-api-1 --format '{{json .NetworkSettings.Networks}}'`,
  `docker inspect nextvisit-llm-ollama-1 --format '{{json .NetworkSettings.Networks}}'` — 두
  컨테이너 모두 네트워크 ID `8047b97986648755...`(`nextvisit-llm_default`)에 연결되어 있음을
  2026-09-17에 직접 확인). 이 호출은 인터넷을 거치지 않으므로 Cloudflare Access나 Tunnel로 보호할
  공개 구간 자체가 없다.
- FE(Cloudflare Pages)는 API를 `api.byguardian.site` 하나로만 호출하도록 설계돼 있다. 그
  요청은 Cloudflare Tunnel(cloudflared, 이 호스트의 systemd 서비스)을 거쳐 `127.0.0.1:8080`의
  API 컨테이너에 도달한다. **단, 이 설계가 완성됐다는 뜻은 아니다.** 2026-09-17 확인:
  Pages 프로젝트는 지금 `byguardian.pages.dev`와 `app.byguardian.site` 두 호스트명 모두에서
  같은 빌드를 서빙한다(`curl https://app.byguardian.site/build.json` → `200`, 본문
  `{"commit":"0e1614b...","apiOrigin":"https://api.byguardian.site"}`). 하지만 API의 CORS는
  아직 `byguardian.pages.dev`만 허용하고 `app.byguardian.site`를 거부한다
  (`curl -X OPTIONS -H 'Origin: https://app.byguardian.site' ... https://api.byguardian.site/me`
  → `403`; 같은 요청을 `Origin: https://byguardian.pages.dev`로 보내면 `200`). `pages.dev` →
  `app` 리다이렉트도 없다. 즉 `app.byguardian.site`는 화면은 뜨지만 모든 API 호출이 지금
  실패한다. 이 CORS 전환은 이 문서가 아니라 후속 도메인 전환 작업(Task 4)이 담당하며, 완료
  조건 3은 그 작업이 끝나야 충족된다.
- Cloudflare Pages가 `main`을 빌드해 프런트엔드를 제공한다. 별도 배포 파이프라인을 새로 만들지
  않는다.
- GitHub self-hosted runner를 통한 노트북 자동 배포는 이번 설계 범위 밖이다(5절).

## 2. 현재 기준선

아래 표와 문장은 모두 [`docs/qa/2026-09-17-single-host-inventory.md`](../../qa/2026-09-17-single-host-inventory.md)를
그대로 인용한 것이며, 다시 파생하지 않는다. 값이 필요한 부분은 원문의 확인 명령을 따라간다.

### 2.1 실행 중인 구성요소

| 컨테이너 | 이미지 | 재시작 정책 | 비고 |
| --- | --- | --- | --- |
| `nextvisit-demo-api-1` | `nextvisit-api:demo` | `unless-stopped` | Spring Boot API, `127.0.0.1:8080` |
| `nextvisit-demo-postgres-1` | `postgres:16-alpine@sha256:cf78e7...` | `unless-stopped` | 호스트 포트 게시 없음, 볼륨 `nextvisit-demo_demo-pg` |
| `nextvisit-llm-ollama-1` | `nextvisit-ollama:0.33.3` | `unless-stopped` | `127.0.0.1:11434`, 볼륨 `nextvisit-llm-ollama-data` |

`cloudflared`는 Docker 컨테이너가 아니라 호스트 systemd 서비스로 `active (running)`, `enabled`
상태다(`/usr/bin/cloudflared --no-autoupdate tunnel run --token-file /etc/cloudflared/token`).

### 2.2 API 환경변수(이름만)

`nextvisit-demo-api-1`에 설정된 환경변수는 다음 이름으로 확인됐다(값은 실사에서도 이 문서에서도
다루지 않는다).

```
NEXTVISIT_CORS_ORIGINS
NEXTVISIT_DB_PASSWORD
NEXTVISIT_DB_URL
NEXTVISIT_DB_USER
NEXTVISIT_DEMO_ENABLED
NEXTVISIT_LLM_API_KEY
NEXTVISIT_LLM_BASE_URL
NEXTVISIT_LLM_ENABLED
NEXTVISIT_LLM_MAX_OUTPUT_TOKENS
NEXTVISIT_LLM_MODEL
```

`NEXTVISIT_DB_PASSWORD`, `NEXTVISIT_CORS_ORIGINS`의 실제 값이 어느 파일에서 오는지는 sudo 권한이
없어 확인하지 못했다(실사 문서 "확인하지 못한 항목 요약" 절).

### 2.3 Ollama와 GPU

받아져 있는 모델 태그는 `qwen3:4b-q4_K_M`, `qwen2.5:7b-instruct-q4_K_M`,
`qwen2.5:3b-instruct-q8_0`, `qwen3:4b-q8_0`이다. 실사 시점 로드되어 있던 모델은
`qwen3:4b-q4_K_M`, `100% GPU`, context 8192로 `compose.demo.yml`의 `NEXTVISIT_LLM_MODEL`
기본값과 일치한다. GPU는 NVIDIA GeForce GTX 1060, driver 580.173.02다.

### 2.4 LLM 생성 상태 — 알려진 문제 (실사 문서 밖 — 2026-09-17 API 컨테이너 로그에서 직접 확인)

이 항목은 2절 서두가 말하는 "실사 문서를 그대로 인용"의 범위 밖이다.
`docs/qa/2026-09-17-single-host-inventory.md`는 이 사실을 다루지 않으며, 아래 값은 같은 날 API
컨테이너 로그에서 별도로 직접 확인한 것이다. `NEXTVISIT_LLM_ENABLED`는 이미 `true`다. 그런데도
현재 모든 생성 시도는 `code=TIMEOUT attempts=3 elapsedMs=135025`(모델 `qwen3:4b-q4_K_M`)로
끝나고 있으며, 그 결과 질문은 매번 템플릿으로 대체된다. 제품은 규칙 엔진과 템플릿만으로 완전히
동작하도록 설계되어 있으므로 이 폴백은 장애가 아니라 설계된 안전 경로다. 이 타임아웃의 원인 조사와
수정은 이 문서의 범위가 아니고 후속 LLM 수정 작업이 담당한다(5절, 완료 조건 7).

### 2.5 백업

`milo` crontab, `/var/lib/nextvisit/backups`, `/opt/nextvisit/backups` 어디에도 백업 흔적이
없다. `root` crontab과 `/home/nextvisit-runner`, `/opt/nextvisit/llm` 내부는 권한이 없어 들여다보지
못했으므로 백업이 그 안에 root 권한으로만 존재할 가능성은 배제되지 않는다. **현재 이 호스트에서 자동
백업이 동작한다는 증거는 없다.**

### 2.6 저장소 접근 제약

배포 호스트의 `/home/nextvisit-runner/demo/compose.demo.yml`,
`/home/nextvisit-runner/ByGuardian/infra/llm/compose.yml`은 `nextvisit-runner` 소유(권한 750)라
`milo`로 열람할 수 없었다. 이 설계 문서가 인용하는 compose 설정은 저장소 안의 동일 이름 파일
(`infra/demo/compose.demo.yml`, `infra/llm/compose.yml`)이며, 배포 호스트의 실제 파일과 바이트
단위로 같다는 것은 확인되지 않았다.

## 3. 아키텍처

```text
보호자/치료사 브라우저
        |
        | HTTPS
        v
app.byguardian.site 또는 byguardian.pages.dev   Cloudflare Pages (main 빌드, 정적 파일)
        |
        | HTTPS  (app.byguardian.site origin은 2026-09-17 현재 다음 hop에서 CORS 403으로 막힘 — 아래 참고)
        v
api.byguardian.site   Cloudflare Tunnel (cloudflared, 호스트 systemd)
        |
        v
127.0.0.1:8080  Spring API 컨테이너 (nextvisit-demo-api-1)
        |
        +--> PostgreSQL 컨테이너 (nextvisit-demo-postgres-1, 호스트 포트 게시 없음)
        |
        `--> NEXTVISIT_LLM_ENABLED=true일 때만
                   |
                   v (Docker 내부 네트워크 nextvisit-llm_default, 인터넷 경유 없음)
             127.0.0.1:11434 / 컨테이너 alias `ollama`
             Ollama 컨테이너 (nextvisit-llm-ollama-1) + GTX 1060
```

Ubuntu 노트북 한 대가 API, PostgreSQL, Ollama, cloudflared를 모두 호스팅한다. 노트북 밖으로
공개된 것은 SSH뿐이며, API·DB·Ollama는 loopback 또는 Docker 내부 네트워크로만 서로 접근한다.
Pages와 이 호스트 사이에는 Tunnel 외의 공개 경로가 없다.

**2026-09-17 현재 상태**: `app.byguardian.site`는 커스텀 도메인 연결이 끝나 페이지 자체는
Pages에서 정상 서빙된다. 그러나 API `NEXTVISIT_CORS_ORIGINS`는 아직 `byguardian.pages.dev`만
허용하고 `app.byguardian.site`에서 온 요청은 preflight 단계에서 403으로 거부한다(1절 참고).
따라서 위 다이어그램 중 `app.byguardian.site → API` 구간은 지금 실제로 끊겨 있고, 완료 조건 3은
아직 충족되지 않은 미완료 상태다. 이 CORS 전환은 Task 4가 담당한다.

## 4. 완료 조건

1. `main` 한 곳에 FE·BE·infra와 실제와 일치하는 문서가 있다.
2. PR에서 FE test/typecheck/build, Java test, infra 정적 검사, 통합 E2E가 hosted CI에서
   통과한다.
3. `app.byguardian.site`가 Pages production 빌드를 제공하고 브라우저는 `api.byguardian.site`만
   호출하며, 모든 client route가 직접 진입·새로고침 뒤에도 복구된다.
4. 호스트는 SSH 외 공개 포트가 없고 API·DB·Ollama는 loopback 전용이며, 외부 `/health`가 DB
   상태와 함께 정상이다.
5. DB 볼륨과 모델 볼륨이 재배포 뒤 유지되고, 백업의 실제 복원 시험을 한 번 통과한다.
6. 노트북 재부팅 뒤 Docker, PostgreSQL, API, Ollama, cloudflared가 수동 조치 없이 복구된다.
7. LLM을 끄거나 Ollama를 내려도 기록 저장과 템플릿 질문이 성공한다.
8. 저장소·CI 로그·보고서에 비밀값과 보호자 원문이 없다.

이 저장소는 2026-09-17 기준 **public**이고(`gh api repos/wantedAIHack/ByGuardian --jq .visibility`
→ `public`, owner type `Organization`), `main`에는 branch protection이 걸려 있지 않다
(`gh api repos/wantedAIHack/ByGuardian/branches/main/protection` → HTTP 404
`Branch not protected`). 즉 완료 조건 2의 hosted CI 통과는 **branch protection으로 강제되지
않는다** — write 권한이 있는 사람은 CI 결과와 무관하게 `main`에 직접 push할 수 있다. 이는 2026-09-10
설계서가 전제했던 요금제 제약(비공개 저장소에서 이 기능이 막힘)과는 다른 상태다: 지금은 저장소가
public이라 branch protection 자체는 설정할 수 있는데도 설정되어 있지 않을 뿐이다. 이 문서는
branch protection 설정을 완료 조건에 새로 추가하지 않지만, 이 공백을 감춘 채 "게이트가 있다"고
쓰지 않는다. 완료 조건 2는 현재 "PR에서 hosted CI가 통과한다"는 관측 가능한 최소 기준이며, 그 통과가
`main` 병합을 막는 강제 장치는 아직 아니라는 사실을 그대로 남긴다.

## 5. 이번 범위 밖

- AWS로의 이전(EC2/ECR/SSM/S3/Caddy 구축).
- 다중 호스트 구성.
- GitHub self-hosted runner를 통한 노트북 자동 배포.
- `GET /me/prep-card`에 `generationStatus` 필드를 추가하는 것.

## 6. 대체 이력

2026-09-10 설계서는 서면 승인 당시 유효했던 계획이지만, 아래 결정들은 실제로 구축되지 않았거나
지금 조건에서 더 이상 적용되지 않는다.

- **AWS EC2/ECR/SSM/S3/Caddy**: 초기 EC2 인스턴스, ECR 리포지터리, SSM 배포 문서, S3 백업
  버킷, Caddy 리버스 프록시 어느 것도 만들어지지 않았다. 대신 이미 준비되어 있던 Ubuntu 노트북에
  API와 PostgreSQL을 직접 배포해 지금까지 운영해 왔다. 실물 없는 AWS 구조를 뒤늦게 다시 구축하는
  대신, 실제로 동작 중인 단일 호스트를 기준선으로 삼는다.
- **공개 `llm.<도메인>` 호스트명**: API와 Ollama가 같은 물리 호스트, 같은 Docker 네트워크
  (`nextvisit-llm_default`)에 있다는 것이 2026-09-17 확인됐다(2.1, 1절 참고). LLM 호출이
  애초에 인터넷을 거치지 않으므로, 이를 위한 별도 공개 호스트명은 불필요한 공격면만 추가한다.
- **Cloudflare Access service token**: `llm.<도메인>`이 없으므로 그 앞단을 보호하던 Access
  정책과 서비스 토큰도 보호할 대상이 없다. Access 구성 자체를 만들지 않는다.
- **GitHub organization runner group**: self-hosted runner를 통한 자동 LLM 배포는 5절에서
  이번 범위 밖으로 뺐다. 2026-09-10 설계서는 이 결정이 "비공개 저장소 + branch protection으로
  증명된 runner 제한"을 전제로 했는데, 저장소는 2026-09-17 기준 public이고 `main`
  branch protection도 걸려 있지 않다(4절 참고). 제한을 증명하지 못하면 runner를 등록하지 않는다는
  2026-09-10 설계서의 원래 원칙은 유지되지만, 지금은 self-hosted runner 배포 자체가 범위 밖이라
  runner group 결정이 적용될 대상이 없다.
