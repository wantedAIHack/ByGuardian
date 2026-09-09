# 전체 서비스 통합·운영 배포 설계

작성일 2026-09-10. 상태는 **서면 승인 대기**다. 사용자가 승인한 큰 방향인
“Cloudflare Pages의 React PWA → AWS EC2의 Spring API/PostgreSQL → Cloudflare
Access/Tunnel 뒤 Ubuntu 노트북 Ollama”를 구현 가능한 운영 경계로 고정한다.

이 문서는 제품 기능을 다시 설계하지 않는다. 제품·규제 규칙은 최상위
`README.md`, API 계약은 `2026-09-05-api-design.md`, 화면 계약은 현재
`feat/frontend`가 가진 `2026-09-06-frontend-design.md`, LLM 안전·노트북 계약은
`2026-09-07-llm-server-design.md`가 소유한다. 이 문서는 그 세 모듈을 하나의
비공개 저장소와 배포 파이프라인으로 합치는 방법만 소유한다.
통합 과정에서 발견한 cross-origin·secret transport 같은 배포 보안 결함은 공개 전
gate로 적되, 실제 코드 변경과 동시에 해당 모듈 설계에도 security addendum을 남긴다.

---

## 0. 결정 요약

- FE, BE, LLM/infra를 **하나의 GitHub Organization 비공개 모노레포**의 `main`에
  합친다. 모듈을 별도 저장소로 쪼개지 않는다.
- 현재 `main`의 BE·LLM 이력 위에 `feat/frontend`를 병합한다. Git의 기계적 병합은
  충돌 없이 가능하지만, 최상위 README와 상태 문구는 사람이 의미를 대조해 하나로
  다시 쓴다.
- FE는 Cloudflare Pages, BE와 PostgreSQL은 서울 리전의 단일 EC2, LLM은 이미
  준비된 Ubuntu/NVIDIA 노트북에 둔다.
- 호스트 이름은 사용자가 고를 하나의 기본 도메인 아래
  `app.<기본도메인>`, `api.<기본도메인>`, `llm.<기본도메인>`으로 고정한다.
- 초기 EC2는 Ubuntu 24.04 LTS, x86_64 `t3.medium`, 암호화된 root 20 GiB와 별도 data
  30 GiB gp3를 기본으로 한다. 부하 측정 뒤 축소할 수 있지만 첫 공개 시점에는 Spring,
  PostgreSQL, Caddy의 합산 메모리와 데이터 보존 여유를 우선한다.
- EC2 운영 stack은 Caddy, Spring API, PostgreSQL 세 컨테이너다. DB와 API 포트는
  Docker 내부에만 두고 Caddy의 80/443만 공개한다. 관리자와 CI는 AWS Systems
  Manager를 사용하고 SSH 22는 기본적으로 열지 않는다.
- GitHub Actions는 OIDC로 짧은 AWS 자격증명을 받고, API 이미지를 immutable ECR에
  올린 뒤 제한된 custom SSM Document로 EC2를 배포한다. 장기 AWS access key와
  CI용 SSH private key는 만들지 않는다.
- 노트북의 Ollama 11434는 외부는 물론 운영 호스트에도 publish하지 않는다.
  `cloudflared`만 Docker 내부에서 접근하고, BE만 Cloudflare Access Service Token으로
  `llm.<기본도메인>`을 호출한다.
- 모든 PR 검증은 GitHub-hosted runner에서 수행한다. 노트북 self-hosted runner에는
  조직 runner group이 정확한 저장소와 정확한 `main` 배포 workflow만 허용할 때만
  작업을 보낸다. 현재 요금제/UI에서 이 제한을 증명할 수 없으면 해당 기능을 제공하는
  요금제로 올리거나 수동 배포를 사용하고 runner를 등록하지 않는다.
- 전체 서비스는 LLM을 끈 상태로 먼저 공개 검증한다. 노트북 GPU, Tunnel, Access,
  재부팅 복구와 실패 폴백을 모두 확인한 뒤 `NEXTVISIT_LLM_ENABLED=true`를 마지막에
  적용한다.
- 대화에 노출된 노트북 로그인 암호는 외부 연결이나 runner 등록 전에 사용자가 직접
  변경한다. 기존 값은 명령, 파일, 이슈, Actions, 작업 보고서 어디에도 복사하지 않는다.

## 1. 현재 기준선과 통합 출발점

### 1.1 저장소 상태

| 대상 | 기준 commit | 현재 담긴 것 |
| --- | --- | --- |
| 로컬 `main` | `e131790` | API/engine, LLM 연동, Ollama/Tunnel, LLM CI/CD와 운영 체크리스트 |
| `origin/main` | `848df38` | LLM 작업 전 API/engine |
| `feat/frontend` | `c471a7b` | 완성된 React PWA와 화면이 요구한 API 보완 12개 파일 |
| 통합 브랜치 | `codex/full-service-integration` | 로컬 `main`에서 분기, 이 설계부터 시작 |

로컬 `main`은 `origin/main`보다 28 commits 앞서고, `feat/frontend`는 공통 기준점보다
40 commits 앞선다. 따라서 원격이나 Organization 이전을 먼저 하지 않는다. 로컬에서
통합·검증·문서 정합성을 끝낸 뒤 리뷰 가능한 한 묶음으로 원격에 올린다.

`git merge-tree --write-tree main feat/frontend`는 tree
`ef66579e5f2c94edfb5b28e83107677825e42d6e`를 만들며 텍스트 충돌을 보고하지 않았다.
이는 **기계적으로 합칠 수 있다**는 증거일 뿐, 두 README의 상반된 상태 설명까지
동시에 참이라는 뜻은 아니다.

현재 확인된 기준선은 다음과 같다.

- FE: `.nvmrc`가 요구하는 Node 22에서 Vitest 205/205와 TypeScript 검사 성공. 실제
  기준선 실행 버전은 22.22.2다.
- BE/engine: Java 21, Gradle 전체 250/250 성공.
- LLM infra: shell 문법/ShellCheck, 가짜 model·smoke, workflow 보안 정책,
  runner-group mutation, CPU/GPU/Tunnel Compose 정적 렌더 성공.
- 기본 Node 26에서 보인 localStorage 테스트 실패는 애플리케이션 결함이 아니라
  저장소가 고정한 Node 22를 무시한 실행 환경 차이였다. CI와 문서는 Node 22만 쓴다.

작업공간 루트의 untracked `docs/service-reference.html`은 현재 FE 구현 전 상태를 담은
개인 작업 산출물이다. 사용자가 별도로 포함하라고 결정하기 전에는 병합, 삭제, 수정,
커밋하지 않는다.

### 1.2 노트북 실측 상태

2026-09-10 `ssh llm`의 읽기 전용 조사 결과다.

- Ubuntu 24.04, x86_64, 8 CPU, 약 24 GiB RAM, root disk 여유 약 206 GiB.
- NVIDIA GTX 1060 6 GiB, driver 580.173.02, Secure Boot 비활성.
- DHCP 예약과 SSH alias는 사용자가 준비했다.
- Docker Engine, Compose plugin, NVIDIA Container Toolkit은 아직 없다.
- 11434는 닫혀 있고 Docker service와 저장소도 아직 없다.
- 현재 SSH 사용자는 관리자 그룹에 있지만 passwordless sudo는 아니다.
- timezone은 UTC이고 시간 동기화는 정상이다.

기존 GPU driver는 다시 설치하지 않는다. 먼저 container toolkit과 Docker runtime을
구성하고 실제 GPU container가 실패할 때만 driver 변경을 별도 진단한다.

## 2. 목표와 완료 조건

### 목표

보호자가 `app.<기본도메인>`에서 입력한 데이터가 HTTPS로 EC2 API에 저장되고, 규칙
엔진 결과가 LLM 가용성과 무관하게 즉시 표시되며, 선택적으로 집 노트북의 LLM이
허용된 범위 안에서 질문 문장만 다듬는 전체 흐름을 재현 가능하게 운영한다.

### 완료 조건

1. 한 `main` checkout에 `frontend/`, `backend/`, `infra/llm/`과 통합 운영 문서가
   함께 있고 각 문서의 상태 설명이 실제 코드와 일치한다.
2. PR에서 FE test/typecheck/build, Java test, LLM 정적 검사, production Compose 검사,
   실제 FE↔BE 핵심 흐름 검사가 모두 GitHub-hosted runner에서 성공한다.
3. `app.<기본도메인>`은 Cloudflare Pages의 고정 production build를 제공하고,
   브라우저는 `api.<기본도메인>` 외의 운영 API를 사용하지 않는다. 모든 client route는
   직접 진입·새로고침 뒤에도 같은 화면을 복구한다.
4. EC2는 DB/API 내부 포트를 공개하지 않고 HTTPS API와 DB 포함 `/health`만 정상
   제공한다. 배포는 GitHub 장기 AWS key나 SSH key 없이 진행된다.
5. DB volume, Caddy state, LLM model volume은 재배포 뒤 유지되고 DB backup의 실제
   복원 시험을 한 번 통과한다.
6. `llm.<기본도메인>`은 Access 인증 요청만 받아 Ollama에 전달하고, 무인증 요청은
   차단하며, 노트북이나 공유기에는 11434 inbound가 없다.
7. GTX 1060에서 모델이 `100% GPU`로 동작하고 45초 미만 smoke를 3회 통과하며,
   재부팅 뒤 수동 재설치나 모델 재다운로드 없이 복구된다.
8. LLM을 끄거나 Tunnel을 끊어도 기록 저장과 템플릿 질문은 성공한다.
9. 배포 활성화 변수는 모든 선행 조건 뒤에만 켜지고, 롤백은 새 배포 진입을 먼저
   닫은 뒤 실행 중인 작업과 서비스를 내린다.
10. 저장소, commit history, CI 로그, shell history, Docker metadata에 노트북 암호,
    Tunnel token, Access secret, DB password, guardian token, 보호자 원문이나 모델
    응답이 노출되지 않는다.

## 3. 전체 아키텍처

```text
보호자/치료사 브라우저
        |
        | HTTPS
        v
app.<domain>  Cloudflare Pages (React PWA, 정적 파일)
        |
        | HTTPS + X-Guardian-Token 또는 읽기 전용 link token
        v
api.<domain>  Caddy on AWS EC2
        |
        +--> Spring API :8080 (Docker internal only)
        |       |
        |       +--> PostgreSQL :5432 (Docker internal only)
        |       |
        |       `--> LLM enabled일 때만 Access service credentials
        |                  |
        |                  v
        |          llm.<domain> Cloudflare Access
        |                  |
        |          outbound-only Cloudflare Tunnel
        |                  |
        |                  v
        |          Ubuntu laptop: cloudflared --> Ollama + GTX 1060
        |
        `--> /health: API + DB만 확인, LLM 상태와 분리

GitHub Organization private repository
        |
        +--> hosted CI --> Cloudflare Pages Git deployment
        +--> OIDC --> AWS IAM --> ECR + S3 release + SSM --> EC2
        `--> restricted org runner group --> Ubuntu laptop deploy
```

의존 방향은 항상 FE → BE → LLM이다. FE는 LLM 주소나 Access 자격증명을 알지 못하고,
Ollama는 사용자 데이터 저장소에 접근하지 않는다. `/health`는 DB까지만 확인하므로
집 노트북이 꺼져도 API가 unhealthy가 되지 않는다.

## 4. 하나의 source of truth 만들기

### 4.1 병합 원칙

통합 브랜치에서 `feat/frontend` 이력을 보존해 merge한 뒤 다음 규칙으로 정리한다.

- `frontend/**`와 FE가 추가한 실제 API 계약은 FE branch 결과를 가져온다.
- `backend/api/**`의 LLM 패키지, migration V2, 동시성·로그 안전 수정은 `main` 결과를
  유지한다.
- 양쪽이 바꾼 API 파일은 “최근 branch 승자”로 통째로 선택하지 않고 두 변경의 테스트
  계약을 모두 만족시킨다. 특히 복구 코드 재발급·기록 밀도·catalog label과
  LLM generation CAS·비동기 폴백이 함께 남아야 한다.
- `.gitignore`는 `*.tsbuildinfo`와 `infra/llm/.env`를 모두 보존하고, 실제 Vite/운영
  env 파일도 ignore하되 `.env.example`만 추적한다.
- FE branch의 `.context/codex-session-id`는 제품 파일이 아닌 로컬 session 식별자다.
  통합 결과에서 제거하고 `.context/`를 ignore한다.
- root의 untracked `docs/service-reference.html`은 통합 범위 밖으로 보존한다.

### 4.2 문서 우선순위

병합 뒤 읽는 순서를 다음처럼 단순화한다.

1. `README.md`: 제품 대상, 절대 규칙, UX, 실제 구현·배포 상태.
2. 이 문서: 전체 런타임 경계와 배포 의사결정.
3. `frontend/README.md`: Node 22 실행, 환경변수, 화면·테스트 지도.
4. `backend/README.md`: engine/API/LLM 코드 지도와 로컬 실행.
5. `docs/operations/full-service-runbook.md`: AWS·Cloudflare·배포·백업·롤백 명령.
6. `infra/llm/README.md`와 `infra/llm/OWNER_CHECKLIST.md`: 노트북 전용 절차.

구현 단계에서 빠진 3번과 5번을 만들고, `backend/README.md`의 “frontend 별도 branch”와
최상위 README의 오래된 FE 설명을 제거한다. 같은 상태를 여러 문서에 복사하지 않고,
상세 명령은 runbook으로 링크한다.

## 5. FE와 공개 웹 경계

### 5.1 Cloudflare Pages

- GitHub Organization에 Cloudflare GitHub App을 설치하되 이 저장소 하나에만 접근을
  허용한다.
- Pages root directory는 `frontend`, production branch는 `main`, install/build는
  `npm ci`와 `npm run build`, output은 `dist`다. 통합 때 `.nvmrc`와 Pages
  `NODE_VERSION`을 실제 검증 버전 22.22.2로 함께 고정한다.
- production 환경의 `VITE_API_BASE`는 `https://api.<기본도메인>`이다. 이는 build-time
  공개 설정이며 secret이 아니다.
- `app.<기본도메인>`을 Pages dashboard에서 먼저 custom domain으로 연결한 뒤 DNS를
  적용한다. CNAME만 수동으로 먼저 만들지 않는다.
- PR preview는 기본적으로 공개될 수 있으므로 Cloudflare Access로 보호하거나 처음에는
  비활성화한다. preview host를 production CORS에 wildcard로 허용하지 않으며, 실제
  API E2E는 CI의 격리된 local stack에서 수행한다.
- `*.pages.dev` production URL은 `app.<기본도메인>`으로 redirect해 사용자가 한 origin만
  보게 한다.
- React Router의 모든 공개 route를 직접 URL로 열고 새로고침하는 deep-link smoke를
  둔다. Pages의 SPA fallback과 PWA service worker update가 과거 asset을 무한 보존하지
  않는지도 production canary에서 확인한다.

### 5.2 브라우저 보안과 CORS

- API CORS allowlist는 정확히 `https://app.<기본도메인>` 하나다. `*`, preview wildcard,
  localhost는 production에 넣지 않는다. 허용 header는 `Content-Type`과
  `X-Guardian-Token`, method는 실제 API의 GET/POST/PUT/PATCH/OPTIONS로 제한하고
  credentialed cookie는 허용하지 않는다.
- Pages의 versioned `_headers`가 FE HTML에 HSTS, `X-Content-Type-Options`,
  `Referrer-Policy`, `Permissions-Policy`와 제한적인 CSP를 붙인다. CSP의 `connect-src`는
  `https://api.<기본도메인>`만 추가하고 `frame-ancestors 'none'`을 포함한다. 실제
  `app.<기본도메인>` HTML 응답의 모든 header와 CSP 위반 여부를 production canary로
  검증한다. API Caddy는 별도로 HSTS, `nosniff`, 민감 응답의 cache 금지와 request size
  제한을 담당한다. API JSON에 CSP를 붙이는 것을 FE 보호로 간주하지 않는다.
- guardian token은 현재 계약대로 브라우저 localStorage와 `X-Guardian-Token`을 쓴다.
  따라서 HTTPS, 외부 script 최소화, `dangerouslySetInnerHTML` 금지와 dependency review를
  배포 gate로 둔다.
- 현재 FE의 공유 주소 `/t/:token`은 bearer 성격의 therapist token을 Pages request URL과
  browser history에 남긴다. 공개 전 공유 형식을 `https://app.<기본도메인>/t#<token>`으로
  바꾸고 FE 설계 문서에도 security addendum을 남긴다. URL fragment는 Pages/HTTP
  referrer로 전송되지 않는다. `Therapist` 화면은 fragment를 읽어 tab-scoped
  `sessionStorage`에 넣고 즉시 `history.replaceState`로 주소에서 제거한 뒤 API
  `/t/{token}`을 호출한다. 이 token은 다른 tab과 공유하지 않고 tab/session 종료 때
  사라지며, invalid/폐기된 링크 응답을 받으면 즉시 지운다.
- 치료사 화면은 third-party script, image, font, analytics를 하나도 불러오지 않고
  `Referrer-Policy: no-referrer`를 사용한다. 테스트는 Pages가 받는 실제 request path가
  token 없는 `/t`인지, capture 뒤 address bar/history에 token이 남지 않는지, 같은 tab의
  scrubbed `/t` 새로고침은 복구되는지, back/forward와 shared fragment의 새 tab은 올바르게
  격리되는지, service worker가 API 응답을 cache하지 않는지를 확인한다.
- Caddy는 외부가 보낸 forwarding header를 신뢰하지 않고 확인한 client address로 다시
  만들며, API Docker network에는 Caddy만 진입할 수 있다. production rate limiter는
  `/demo`를 IP당 시간당 5회, `/cases`와 `/guardians/recover` 합계를 IP당 시간당 20회로
  제한하고 고정 429 응답만 낸다. 공개 `POST /demo`는 이 gate와
  `NEXTVISIT_DEMO_ENABLED`의 명시적 production 값이 모두 있을 때만 켠다.

## 6. AWS EC2와 데이터 경계

### 6.1 초기 인프라

첫 배포 기본값은 다음과 같다.

| 항목 | 결정 |
| --- | --- |
| 리전 | `ap-northeast-2` (서울) |
| OS/arch | Ubuntu Server 24.04 LTS, x86_64 |
| 크기 | `t3.medium`; 측정 뒤에만 축소 |
| root disk | 암호화 gp3 20 GiB, delete-on-termination `true` |
| data disk | 암호화 gp3 30 GiB, delete-on-termination `false`, `/var/lib/nextvisit` |
| 주소 | Elastic IP + `api.<기본도메인>` DNS-only A record |
| inbound | 80/443, SSH 22 기본 폐쇄 |
| admin/deploy | SSM Session Manager / Run Command |
| instance metadata | IMDSv2 required, response hop limit 1, public IPv6 미할당 |

최상위 README의 `t4g.small`은 초기 비용 추정이었다. 이 문서는 첫 공개 배포에서
x86_64 build 경로를 단순하게 유지하고 세 컨테이너에 4 GiB를 확보하기 위해
`t3.medium`으로 의도적으로 보수화한다. 실제 7일 memory/CPU 지표가 충분할 때만
`t3.small` 또는 ARM image 검증 뒤 `t4g` 계열로 내린다.

AWS 계정 예산 경보를 월간 예상 비용의 80%와 100%에 먼저 설정한다. EC2에는
SSM managed-instance 통신, 정확한 ECR repository pull, 정확한 release S3 prefix read,
정확한 Parameter Store path read와 그 전용 KMS key decrypt, backup S3 prefix write와
그 전용 KMS key encrypt만 가능한 instance role을 붙인다. backup object read와 KMS
decrypt는 상시 role에서 제외한다.
ECR repository는 tag immutability를 켜고 `latest` tag를 사용하지 않는다.
배포 전 검사는 예상 EBS volume이 `/var/lib/nextvisit`에 실제 mount됐는지 확인하고,
없으면 root disk에 새 DB를 만드는 대신 실패한다. CloudFormation의 data EBS와 backup
bucket에는 `DeletionPolicy`와 `UpdateReplacePolicy`를 `Retain`으로 두어 stack 삭제가
곧 사용자 데이터 삭제가 되지 않게 한다.

API container가 host의 instance role을 훔치지 못하게 `DOCKER-USER` chain에서
`169.254.169.254`를 향한 container traffic을 거부하고 부팅 때 복원한다. 나중에 IPv6를
켜면 IPv6 metadata endpoint도 같은 방식으로 막기 전에는 container를 시작하지 않는다.
acceptance는 host의 SSM managed-node/Session Manager가 정상인 동시에 API container의
metadata token 요청이 timeout이 아니라 명시적으로 차단되는 것을 확인한다.

### 6.2 production Compose

저장소에 BE multi-stage Dockerfile과 production Compose를 추가한다.

- `caddy`: 80/443 publish, `api`로만 reverse proxy. Caddy data/config는 data disk에 보존.
- `api`: ECR의 full commit SHA/digest image, 내부 8080만 expose, DB health 뒤 시작.
- `postgres`: 고정 major/minor image digest, 내부 5432만 expose, data disk bind mount,
  healthcheck. DB secret은 `POSTGRES_PASSWORD_FILE=/run/secrets/db_password`로만 읽는다.
- 각 컨테이너는 CPU/memory와 log rotation 한도를 명시하고 root filesystem을 가능한
  범위에서 read-only로 둔다. API/Caddy/PostgreSQL 어디에도 Docker socket, host PID
  namespace나 host filesystem root를 mount하지 않는다.
- `docker compose up --wait` 뒤 외부 `/health`가 `status=ok`, `db=up`일 때만 배포를
  성공 처리한다.

Spring production의 **비밀이 아닌 environment**는 최소 다음을 명시한다.

```text
NEXTVISIT_DB_URL
NEXTVISIT_DB_USER
NEXTVISIT_CORS_ORIGINS=https://app.<기본도메인>
NEXTVISIT_DEMO_ENABLED=false   # rate limit 검증 뒤에만 true
NEXTVISIT_LLM_ENABLED=false    # 전체 연결 검증의 마지막까지 false
NEXTVISIT_LLM_BASE_URL=https://llm.<기본도메인>/v1
NEXTVISIT_LLM_MODEL=qwen3:4b-q8_0
```

DB password와 Access Client ID/Secret 같은 비밀값은 AWS Parameter Store SecureString의
`/nextvisit/prod/...` 아래에 둔다. CloudFormation은 분리된 secret/backup KMS key, IAM
policy, 예상 parameter path와 validation output만 만들고
`SecureString` 값 자체는 만들지 않는다. 사용자가 보호된 AWS dashboard 입력 또는 값이
명령 인자·history·log에 남지 않는 감사된 post-stack 절차로 값을 생성한다. 배포
preflight는 값을 읽거나 출력하지 않은 채 각 parameter의 존재, `SecureString` type,
KMS key와 exact path만 확인하고 하나라도 다르면 실패한다. EC2 배포 스크립트가
world-readable이 아닌 개별 secret 파일로 가져오고, 각 파일은 root와
그 secret을 소비하는 고정 non-root container UID/GID만 읽게 한다. Compose secret으로
`/run/secrets`에 read-only mount하고 Spring은 `configtree` import로 이 파일을 읽는다.
`application-production.yml`이 `spring.config.import=configtree:/run/secrets/`를 선언한다.
config tree key는 `spring.datasource.password`, `nextvisit.llm.cf-access-client-id`,
`nextvisit.llm.cf-access-client-secret`이다. 이 DB password와 Access Client ID/Secret을
container environment나 command에 넣지 않으며,
binding과 각 container의 `docker inspect .Config.Env/.Config.Cmd/.Config.Labels`
비노출을 integration test로 고정한다. 비밀은 GitHub에서 EC2로 전달하지 않는다.
production profile은 secret file이 없거나 비어 있거나 예상 mode/owner가 아니면 기본
개발용 password로 내려가지 않고 시작 전에 실패한다.

### 6.3 migration, backup, rollback

- Flyway migration은 배포 시작 시 API가 실행하며, 공개 전까지는 additive migration만
  허용한다. 한 release 전 API와 호환되지 않는 destructive migration은 별도 승인한다.
- 단일 EC2 MVP의 복구 목표는 **RPO 5분 이하, RTO 2시간 이하**다. PostgreSQL
  `archive_timeout=180s`와 `archive_command`가 WAL을 data disk의 전용 spool에 임시 suffix로
  복사하고 `fsync`한 뒤 같은 filesystem에서 원자적으로 최종 이름으로 바꾼다. root-owned
  host systemd uploader는 30초 간격으로 최종 이름만 checksum과 함께 S3에 올리고 remote
  checksum을 확인한 뒤에만 local WAL을 정리한다. DB container는 metadata에 접근하지 않고
  host uploader만 instance role을 쓴다. latest S3 WAL age와 spool backlog가 기준을 넘으면
  경보하고, 부하 상태에서 실제 측정한 RPO가 5분 이하인 것을 공개 전 activation gate로
  둔다. versioning·SSE-KMS·lifecycle이 설정된 prefix에 매일 base backup도 만들며 기본
  보존 기간은 14일이다.
- 새 Flyway migration이 있는 release는 현재 DB의 pre-migration logical dump를 먼저
  만들고 S3 upload와 checksum을 확인한 뒤에만 API를 교체한다. 이 gate가 실패하면
  migration을 시작하지 않는다.
- 상시 EC2 instance role은 backup prefix 쓰기만 허용한다. 복원은 MFA/명시적 승인 뒤
  짧게 assume하는 별도 `nextvisit-restore` role이 선택한 backup/WAL object의 read와
  KMS decrypt만 받는다.
- 공개 전과 backup 형식 변경 뒤에는 production DB를 덮지 않고 격리된 임시 volume/DB로
  최신 base backup+WAL을 복원해 핵심 조회와 RPO/RTO를 검증한다. 검증이 끝난 임시
  자원은 승인된 절차로 제거한다. backup 생성 성공만으로 완료 처리하지 않는다.
- release bundle과 API image는 full commit SHA로 식별한다. health 실패 시 이전 digest와
  이전 release symlink로 되돌린다. DB migration이 하위 호환되지 않으면 자동 rollback을
  시도하지 않고 서비스를 닫은 채 복구 절차로 전환한다.

제품의 “입력 관찰을 버리지 않는다”는 정상 애플리케이션 경로의 불변식이다. 단일 노드
MVP가 물리 장애에서도 절대 0초 데이터 손실을 보장한다고 과장하지 않는다. 5분보다
엄격한 RPO가 실제 사용자 데이터의 출시 조건이면 이 구조로 공개하지 않고 RDS/PITR로
범위를 올린다.

## 7. Ubuntu LLM 노트북

기존 `infra/llm` 설계와 `OWNER_CHECKLIST.md`를 유지하고, bootstrap만 재현 가능하게
추가한다. 단, 아래 `llm.token`/`--token-file`은 기존 `/etc/nextvisit/llm.env` 방식이
Docker metadata에 token을 남긴다는 점을 해결하는 **LLM 보안 계약 개정**이다. 구현
commit 하나에서 `2026-09-07-llm-server-design.md`, `infra/llm/README.md`,
`OWNER_CHECKLIST.md`, `compose.tunnel.yml`, deploy workflow와 관련 shell/policy tests를
함께 바꾸고, 이전 env 방식이 남으면 배포를 실패시킨다.

### 7.1 bootstrap 경계

구현할 `infra/llm/scripts/bootstrap-ubuntu-host.sh`는 다음을 만족해야 한다.

- Ubuntu 24.04/x86_64와 기존 NVIDIA driver를 먼저 확인하고 예상과 다르면 중단한다.
- Docker 공식 apt repository에서 Engine과 Compose plugin을 설치한다.
- NVIDIA 공식 repository에서 Container Toolkit을 설치하고
  `nvidia-ctk runtime configure --runtime=docker` 뒤 Docker를 재시작한다.
- locked service account `nextvisit-runner`, group-traversable/read-protected
  `/etc/nextvisit`와 `llm.token`, Docker 접근, runner와 Compose service의 systemd 복구
  경계를 만든다.
- AC power 상태에서 suspend, idle sleep, lid close로 서비스가 멈추지 않도록 명시하고
  변경 전 파일을 백업한다.
- 같은 버전에서 재실행해도 중복 repository, 사용자, 설정을 만들지 않는 idempotent
  작업이어야 한다.
- 암호, Tunnel token, runner registration token을 인자로 받거나 로그에 출력하지 않는다.

에이전트는 audited script를 노트북에 전달하고 설명할 수 있지만 sudo 암호를 대신
입력하지 않는다. 사용자가 SSH/로컬 터미널에서 직접 실행하고 재부팅도 승인 시점에
직접 수행한다.

### 7.2 runtime와 네트워크

- `qwen3:4b-q8_0`, Ollama `0.33.3` digest, context 2048과 named model volume을 유지한다.
- production은 `compose.yml + compose.gpu.yml + compose.tunnel.yml` 세 파일을 같은
  project name `nextvisit-llm`으로 적용한다.
- `cloudflared`는 outbound connection만 만들며 Ollama에는 host port binding이 없다.
- Tunnel token은 `/etc/nextvisit/llm.token`에 두고 host의 일반 사용자가 읽지 못하게
  한다. 공식 cloudflared image가 `65532:65532` non-root로 실행되므로 token 파일은
  `root:65532`, mode `0440`으로 만들고 이를 정적·live test로 확인한다.
  Compose secret으로 `/run/secrets/tunnel_token`에 mount한 뒤 pinned cloudflared가
  지원하는 `--token-file`을 사용한다. 기존 `TUNNEL_TOKEN` env 방식은 Docker
  `Config.Env`에 값을 남기므로 통합 과정에서 제거한다.
- 개인 관리자 계정의 `ssh llm`은 bootstrap/장애 대응에만 사용하고 Actions runtime
  자격증명으로 쓰지 않는다.

## 8. Cloudflare 외부 설정

사용자가 기본 도메인을 구매하거나 보유 도메인을 Cloudflare zone으로 추가한 뒤 다음
세 경계를 만든다.

| hostname | Cloudflare 역할 | origin |
| --- | --- | --- |
| `app.<domain>` | Pages custom domain | Pages project |
| `api.<domain>` | DNS-only A record | EC2 Elastic IP + Caddy HTTPS |
| `llm.<domain>` | proxied Tunnel route + Access | laptop `cloudflared` → `ollama:11434` |

먼저 LLM Access application과 정책을 만들고 **그 뒤에만** Tunnel public hostname을
연결한다. 반대 순서로 만들어 origin이 잠시 무보호 상태가 되는 창을 허용하지 않는다.
Access policy는 Action `Service Auth`, Include selector `Service Token`, Value는 **BE 전용
token 하나**로 제한한다. EC2 Elastic IP가 확정되면 같은 policy의 Require IP 조건으로
그 outbound 주소도 제한하고 실제 BE smoke로 검증한다. Client ID/Secret은 생성 직후
AWS Parameter Store에 한 번 저장하고 일반 파일이나 GitHub에 복사하지 않는다. 무인증
요청이 302/401/403으로 막히고 인증 요청만 200이 되는 것을 응답 본문 비노출 smoke로
확인한다.

Tunnel token은 노트북에만, Access token은 BE에만 둔다. 둘을 한 secret store나 한
env 파일에 합치지 않는다. token 노출 대응은 다음처럼 분리한다.

- Access token 의심: 즉시 삭제 → 새 token 발급 → Access policy의 유일한 Include 교체
  → AWS secret 교체 → LLM disabled 상태로 API 재시작 → 양방향 smoke.
- Tunnel token 의심: token 회전 → 기존 connector 전부 강제 종료 → 노트북 token 파일 교체
  → 정상 cloudflared 재생성 → 양방향 smoke.

## 9. GitHub Organization과 CI/CD 신뢰 경계

### 9.1 비공개 저장소

GitHub Organization의 private repository에서도 이 구성이 가능하다. 기존 repository를
Organization으로 이전해 commit, branches와 이력을 유지하는 방식을 우선한다. 이전 전
통합 branch를 검증하고, 이전 직후 remote URL, default branch, Actions permissions,
branch protection과 collaborator 권한을 다시 확인한다. 특정 상품 이름을 먼저 결제하지
않고 실제 Organization UI/API가 아래 gate를 제공하는지 확인한 뒤 가장 작은 적합
요금제를 선택한다.

필수 정책은 다음과 같다.

- `main` 직접 push 금지, PR과 required hosted CI 사용.
- Actions default permission은 read-only.
- workflow 변경은 CODEOWNERS 대상이며 승인 전 배포하지 않는다.
- fork PR 또는 `pull_request_target`에서 secret/self-hosted runner를 사용하지 않는다.
- third-party action은 release tag가 아닌 검토한 full commit SHA로 고정한다.

GitHub Free도 Organization private repository 자체는 만들 수 있지만 private branch
protection 등 일부 보호 기능은 요금제에 따라 제한될 수 있다. private repository의
required deployment reviewer는 Free/Pro/Team에서 사용할 수 없다는 현재 문서를 전제로,
어떤 요금제에서도 reviewer만을 안전 장치로 간주하지 않는다. runner 등록이나 결제 전에
runner-group API/UI의 `restricted_to_workflows`, `selected_workflows`,
`workflow_restrictions_read_only`와 selected repository 값이 실제로 원하는 상태가 되는지
읽어 증거를 남긴다. 다음 중 하나만 허용한다.

1. 정확한 selected repository와 selected workflow 제한이 확인되면 자동 LLM 배포.
2. 확인되지 않으면 해당 제한을 제공하는 GitHub 요금제로 업그레이드.
3. 업그레이드하지 않으면 노트북 runner를 등록하지 않고 audited 수동 배포 유지.

### 9.2 hosted CI

모든 PR에서 관련 경로를 식별하되 required check 자체는 항상 종료 상태를 반환한다.

- FE: Node 22, `npm ci`, test, typecheck, production build.
- BE: Java 21, Gradle clean test.
- infra: shell syntax/ShellCheck, fake model/smoke, workflow policy/mutation, 세 Compose render.
- integration: PostgreSQL과 LLM-disabled API를 띄우고 built FE를 실제 브라우저로 열어
  demo 또는 onboarding → guardian 인증 조회 → 기록 저장 → 준비 카드/치료사 링크의
  핵심 흐름과 CORS를 검증한다.
- compatibility: 현재 commit끼리뿐 아니라 `{새 FE, 직전 main BE}`와
  `{직전 main FE, 새 BE}` 두 조합의 핵심 contract/E2E를 실행한다. 공개 API field 제거,
  rename과 기존 의미 변경은 대회 종료 후 30일인 **2026-10-20까지 금지**하고 응답 field는
  additive하게만 늘린다. 그 뒤에도 breaking change는 기존 경로를 바꾸지 않고 versioned
  새 endpoint로 낸다. 설치되거나 오래 열린 PWA가 사라졌다고 가정하지 않는다.
- security: dependency review, secret scan, Docker build, production Compose가 DB/API
  port를 publish하지 않는 정적 assertion.

PR 코드는 어떤 self-hosted runner에서도 실행하지 않는다.
첫 통합 공개에는 직전 production FE가 없으므로 새 BE를 먼저 배포하고 새 FE와의 전체
E2E를 통과한 release를 compatibility 기준 tag로 남긴다. 그 다음 release부터 위 두
N/N-1 조합을 필수로 한다.

### 9.3 FE 배포

branch protection 때문에 `main`에 들어온 commit은 hosted CI를 이미 통과한 상태다.
Cloudflare Pages Git integration이 그 commit을 `frontend/`에서 다시 build하고
production에 배포한다. Pages에 GitHub write token을 저장소 secret으로 넣지 않는다.
Pages와 BE는 같은 commit에서도 완료 순서가 보장되지 않으므로 앞 절의 N/N-1
compatibility matrix가 필수다. 배포 후 `app.<domain>`의 build metadata에 주입한 full
commit SHA, API base, security headers와 핵심 사용자 흐름을 canary로 확인한다. 실패하면
Pages dashboard에서 직전 successful deployment를 즉시 production으로 rollback하고,
새 배포 원인을 고치거나 `main` revert를 별도 PR로 진행한다. FE rollback 중에도 새 BE가
직전 FE 계약을 계속 받아야 한다.

### 9.4 BE 배포

별도 `Deploy Backend Production` workflow는 `main`과 수동 실행만 받고
`BACKEND_DEPLOY_ENABLED=true`일 때만 동작한다.

1. hosted CI 결과와 exact commit을 확인한다.
2. GitHub OIDC token으로 exact AWS role을 assume한다.
3. API image를 ECR에 full SHA로 push하고 digest를 고정한다.
4. secret 없는 Compose/Caddy/deploy bundle을 versioned S3 prefix에 올리고 checksum을
   기록한다.
5. 허용 명령과 인자를 고정한 custom SSM Document를 `Project=nextvisit`,
   `Environment=production` tag를 모두 가진 EC2 한 대에만 실행한다.
6. EC2가 bundle checksum과 image digest를 확인하고 secret을 Parameter Store에서
   읽어 stack을 적용한다.
7. 내부와 외부 `/health` 성공 뒤 release를 확정하고, 실패하면 이전 release로
   되돌린다.

OIDC trust는 정확한 Organization/repository와 `refs/heads/main` subject, audience
`sts.amazonaws.com`을 `StringEquals`로 제한한다. workflow job 권한은
`contents: read`, `id-token: write`만 주고 AWS role은 ECR/S3/SSM의 위 resource만
허용한다. Organization 이전 뒤 실제 OIDC token의 `sub`가 이름 기반인지 영구
Organization/repository ID를 포함한 immutable 형식인지 확인한 다음 trust policy를
생성한다. command output에 env, HTTP body, Docker inspect를 남기지 않는다.

### 9.5 LLM 배포

기존 `Deploy LLM Laptop`의 fail-closed 조건을 유지한다.

- Organization runner group `llm-production`.
- labels `[self-hosted, linux, llm]`.
- exact workflow `.github/workflows/llm-deploy.yml@refs/heads/main`만 허용.
- repository variable `LLM_DEPLOY_ENABLED=true`와 `main` gate.
- concurrency group 하나, PR trigger 없음, `contents: read`만.
- runner 등록 token은 history를 끈 전용 사용자 shell에 한 번만 입력.

## 10. 초기 연결과 활성화 순서

순서를 건너뛰지 않는다.

1. 사용자가 노트북 로그인 암호를 직접 변경한다.
2. 통합 branch에서 FE를 merge하고 문서/API 의미 충돌을 정리한다.
3. local 전체 test와 LLM-disabled integration E2E를 통과한다.
4. 사용자가 GitHub Organization을 만들고 private repository 이전, 요금제 기능,
   branch protection을 확인한다.
5. 사용자가 AWS 계정의 MFA·budget과 apply를 승인한다. 에이전트가 검토된
   CloudFormation을 사용자 인증 session에서 적용해 EC2/ECR/S3/SSM/KMS와 Parameter
   Store의 expected paths·IAM·validation output을 재현 가능하게 만든다. 사용자는 보호된
   입력 절차로 `SecureString` 값을 별도 생성하고, 배포 preflight는 값을 노출하지 않고
   type·KMS key·path·presence를 확인한다.
6. 사용자가 기본 도메인을 선택·구매하고 Cloudflare zone/GitHub App 권한을 승인한다.
   에이전트가 검토된 설정 절차로 Pages/DNS/Access 구성을 적용·검증한다.
7. BE를 `NEXTVISIT_LLM_ENABLED=false`로 배포해 HTTPS, DB, backup/restore, rate limit,
   rollback을 검증한다.
8. FE를 배포하고 실제 브라우저의 핵심 흐름을 LLM 없이 통과한다.
9. 노트북 bootstrap과 reboot 뒤 GPU local smoke를 통과한다.
10. exact Access Service Auth policy를 먼저 만든 뒤 Tunnel hostname을 연결하고 외부
    인증/무인증 smoke를 통과한다.
11. Organization runner restriction을 증명한 경우에만 노트북 runner를 등록하고,
    자동 LLM 배포를 한 번 검증한다.
12. BE secret을 최종 확인한 뒤 `NEXTVISIT_LLM_ENABLED=true`로 바꾸고 실제 비동기
    `LLM_PENDING → LLM_DONE`과 장애 시 template fallback을 검증한다.

단계 12 전까지 제품은 규칙 엔진과 template로 완전하게 동작해야 한다.

## 11. 실패와 롤백

### BE release 실패

- 새 배포 admission인 `BACKEND_DEPLOY_ENABLED`를 먼저 false로 바꾼다.
- queued/running backend deploy를 모두 취소하고 다시 조회한다.
- LLM을 false로 둔 채 이전 image digest/release bundle로 되돌린다.
- DB migration 호환성 때문에 rollback이 안전하지 않으면 쓰기 트래픽을 닫고 restore
  또는 forward fix를 선택한다.

### LLM/Tunnel 실패

- 새 admission인 `LLM_DEPLOY_ENABLED`를 먼저 false로 바꾼다.
- queued/running laptop deploy를 취소하고 다시 조회한다.
- BE `NEXTVISIT_LLM_ENABLED=false`로 재시작한다.
- 필요하면 volume을 보존한 채 LLM stack을 내린다.
- API 저장·조회와 template 질문이 정상인지 확인한 뒤 원인을 조사한다.

### 노트북 offline

이는 API 장애가 아니다. bounded timeout/retry 뒤 해당 generation만 `LLM_FAILED`가 되고
template가 유지돼야 한다. EC2 health와 FE availability 경보는 울리지 않는다.

### 비밀 노출

일반 롤백 순서를 기다리지 않고 의심된 자격증명부터 즉시 revoke/rotate한다. 새 값은
각자의 단일 secret store에만 넣고, 서비스 재시작과 authenticated/unauthenticated
smoke 전에는 배포와 LLM을 다시 켜지 않는다.

## 12. 관측과 운영 기록

- API application logs에는 request ID, route template, status, latency만 남기고 guardian
  token, path의 therapist token, request/response body는 남기지 않는다. Caddy access log는
  `/t/{token}`의 실제 URL을 기록할 수 있으므로 안전한 field redaction을 검증하기 전까지
  끈다.
- Cloudflare Pages Web Analytics와 Logpush는 초기 공개에서 끈다. fragment 전환 뒤
  Pages가 받는 `/t` request에 token이 없고 어떤 third-party request/referrer에도 token이
  없는 것을 browser trace로 확인한 뒤에만 집계 기능을 별도 승인한다.
- LLM logs는 기존 계약대로 model, generation ID, duration, attempt, fixed result code만
  남긴다. prompt와 content는 남기지 않는다.
- Docker json logs는 크기와 file 수를 제한하고 CloudWatch 기본 보존 기간은 7일로 둔다.
  CloudWatch로 보낼 경우 같은 redaction 규칙을 먼저 테스트한다.
- 최소 경보는 EC2 status check, disk 80%, external `/health`, latest S3 WAL age와 spool
  backlog, daily base backup 누락, Access token 만료 7일 전이다.
- 운영 기록에는 commit SHA, image digest, 실행 시각, 고정 결과, latency, GPU 비율만
  남긴다. 사용자 데이터와 secret은 증거가 아니다.

## 13. 구현 단계

### Phase A — source 통합

- `feat/frontend` merge, session artifact 제거, ignore 합집합.
- 양쪽 API 변경을 함께 보존하고 root/backend 문서 상태 통합.
- Node 22 FE test/typecheck/build, Java 21 clean test, LLM 정적 test.

### Phase B — 통합 검증

- local production-like PostgreSQL/API/FE stack.
- 실제 browser critical-flow E2E와 CORS, token 전환, demo rate limit.
- 하나의 hosted CI entry check와 path-aware jobs.

### Phase C — BE production artifacts

- API Dockerfile, Caddy config, production Compose, release/rollback/backup scripts.
- AWS native CloudFormation으로 EC2/EBS/EIP/ECR/S3/SSM/KMS/OIDC/IAM을 선언하고 policy
  tests. `SecureString` 값은 stack resource가 아니라 앞 절의 별도 보호 입력과 preflight로
  관리한다.
- `NEXTVISIT_LLM_ENABLED=false` EC2 deployment.

### Phase D — FE/도메인

- Pages project와 `app`, EC2의 `api`, exact production CORS.
- 공개 canary와 full critical journey.

### Phase E — Ubuntu/LLM

- audited bootstrap, user-entered sudo, reboot, GPU/local smoke.
- Tunnel/Access, external silent smoke, restricted runner, automated deployment.

### Phase F — 최종 활성화와 인수

- backup restore와 양쪽 rollback rehearsal.
- LLM enable, async success/fallback E2E.
- 전체 runbook과 owner checklist에 실제 resource 이름·검증 일시를 secret 없이 기록.

## 14. 사용자가 직접 해야 하는 일

외부 설정을 사용자의 수작업으로 떠넘기지 않는다. 책임은 다음과 같이 나눈다.

| 작업 | 사용자 | 에이전트 |
| --- | --- | --- |
| 계정·결제 | GitHub/AWS/Cloudflare 계정, MFA, 약관, budget 승인 | 필요한 최소 요금제/권한과 예상 변경 제시 |
| source와 인프라 | PR/merge와 최종 apply 승인 | merge, tests, CloudFormation, Compose, policies, runbook 작성·검토 |
| 실제 apply | 변경 범위와 비용을 보고 명시적으로 승인 | 승인된 인증 session에서 IaC 적용, 결과·drift 검증 |
| secret | dashboard/host의 보호된 입력창에 직접 입력 | 이름·저장 위치·검증 절차만 제공, 값을 채팅으로 요구하지 않음 |
| 노트북 root 작업 | sudo 입력과 reboot 시점 승인 | idempotent bootstrap 제공, 비특권 검사 자동화 |
| 최종 활성화 | 증거 검토 후 enable 승인 | canary, rollback rehearsal, 결과 문서화 |

다음은 계정 소유자만 할 수 있거나 에이전트가 대신하지 않는 작업이다.

1. 노출된 노트북 로그인 암호 변경과 sudo 입력.
2. GitHub Organization 이름 선택·생성, 결제 요금제 선택, repository 이전 승인.
3. 서비스 기본 도메인 선택·구매와 Cloudflare 계정 결제/약관 승인.
4. AWS 계정 MFA, 결제 수단, budget 상한 확인과 최종 리전/월 비용 승인.
5. Cloudflare Tunnel/Access token과 GitHub runner registration token을 각 dashboard에서
   생성해 지정된 secret store에 직접 입력.
6. 노트북 재부팅 시점 승인과 GPU/전원/유선 또는 안정적 Wi-Fi 상태 확인.
7. 실제 서비스에 테스트가 아닌 사용자 데이터를 넣기 전 개인정보 처리·보유·삭제
   정책과 대회 공개 범위를 최종 판단.

로컬 source 통합과 테스트는 외부 secret 없이 시작할 수 있다. Phase C의 실제 apply
전에는 Organization/repository 이름, 기본 도메인, AWS account ID·리전·budget과 위
표의 각 외부 변경 승인이 필요하다. secret 값은 채팅으로 받지 않고 사용자가 dashboard
또는 대상 host의 보호된 입력창에 직접 넣는다.

## 15. 이번 범위 밖

- RDS, Multi-AZ, 다중 EC2, Kubernetes, autoscaling, cross-region disaster recovery.
- LLM 다중 노드나 노트북 장애 시 다른 model provider 자동 전환.
- iOS/Android native app, 사용자 계정·결제, 치료사 입력 기능.
- 제품의 의료·규제 책임을 자동으로 해결하는 기능. 현재 금지 규칙과 개인정보 운영
  판단은 계속 사람이 검토한다.
- FE/BE/LLM repository 분리. 실제 팀·배포 주기가 커져 독립 release가 필요해질 때만
  다시 검토한다.

## 16. 공식 참고 자료

- [GitHub: AWS에서 OIDC 구성](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- [AWS: SSM Run Command 설정과 tag 기반 제한](https://docs.aws.amazon.com/systems-manager/latest/userguide/run-command-setting-up.html)
- [AWS: EC2의 Systems Manager 권한](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-instance-permissions.html)
- [AWS: Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [AWS: ECR tag immutability](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-tag-mutability.html)
- [AWS: EC2 instance metadata options](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-options.html)
- [Docker: Ubuntu Engine 설치](https://docs.docker.com/engine/install/ubuntu/)
- [NVIDIA: Container Toolkit 설치](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)
- [PostgreSQL: continuous archiving과 PITR](https://www.postgresql.org/docs/current/continuous-archiving.html)
- [Cloudflare Pages: custom domain](https://developers.cloudflare.com/pages/configuration/custom-domains/)
- [Cloudflare Pages: Git 연동](https://developers.cloudflare.com/pages/get-started/git-integration/)
- [Cloudflare Pages: preview 보호](https://developers.cloudflare.com/pages/configuration/preview-deployments/)
- [Cloudflare Pages: `_headers`](https://developers.cloudflare.com/pages/configuration/headers/)
- [Cloudflare Tunnel: outbound-only 연결](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/)
- [Cloudflare Tunnel: `--token-file` run parameter](https://developers.cloudflare.com/tunnel/advanced/run-parameters/#token-file)
- [Cloudflare Access: Service Token](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)
- [GitHub: self-hosted runner group 접근 제한](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/manage-access)
- [GitHub: deployment environment와 private repository 제한](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
