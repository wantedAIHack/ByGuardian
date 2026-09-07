# LLM 서버·BE 연동·노트북 배포 설계

승인일 2026-09-07. 최상위 `README.md`의 제품·규제 규칙과 `2026-09-05-api-design.md`의 API 계약을 실제 LLM 연동과 배포 구성으로 옮기는 문서다. 충돌하면 최상위 `README.md`가 이긴다.

---

## 0. 결정 요약

- LLM 서버는 별도 FastAPI 게이트웨이 없이 Ollama의 OpenAI 호환 API를 직접 노출한다.
- 모델 기본값은 `qwen3:4b-q8_0`, Ollama 기본 이미지는 안정 태그 `0.33.3`으로 고정한다.
- Spring BE가 프롬프트, 비동기 실행, 출력 검증, 재시도, 캐시와 템플릿 폴백을 소유한다.
- 첫 완성 흐름은 규칙 엔진이 고른 최대 세 질문의 문장 다듬기다.
- 자유 기록 정제·자유 기록의 LLM 패턴 추출·음성 텍스트 정리는 이번 범위가 아니다. 같은 `LlmClient` 포트를 재사용할 수 있는 구조만 만든다.
- 로컬 macOS에서는 CPU Docker 구성과 가짜 OpenAI 서버로 검증한다. Ubuntu/NVIDIA 실기 검증은 노트북 준비 뒤 별도 체크리스트에 따라 수행한다.
- GitHub Actions CI는 GitHub-hosted runner, 배포는 저장소 전용 `self-hosted, linux, llm` runner를 사용한다.
- 현재 저장소는 비공개다. 그래도 PR 코드는 자체 호스팅 runner에서 실행하지 않고 배포 권한을 최소화한다.

## 1. 목표와 성공 조건

### 목표

주간 기록 저장을 LLM 가용성과 분리한 채, 안전한 질문 문장을 백그라운드에서 생성해 기존 `question_cache`에 반영한다. 같은 저장소에서 Ollama 컨테이너, Cloudflare Tunnel, CI/CD와 Ubuntu 준비 절차까지 관리한다.

### 성공 조건

1. LLM이 꺼져 있거나 느리거나 잘못된 문장을 내도 주간 기록 저장과 준비 카드 조회가 정상 동작한다.
2. 준비 카드에는 항상 규칙 엔진의 템플릿 또는 검증을 통과한 LLM 문장이 있다.
3. 설정만 바꾸면 로컬 Ollama, Cloudflare Tunnel 뒤 Ollama, OpenAI 호환 호스팅 API 사이를 전환할 수 있다.
4. macOS에서 Java 테스트, Docker 이미지 빌드, CPU Compose 상태 확인과 OpenAI 호환 smoke를 실행할 수 있다.
5. Ubuntu 노트북이 준비되면 문서의 절차만으로 NVIDIA 구성, runner 등록, Tunnel 연결과 자동 배포를 완료할 수 있다.
6. 모델 입력·출력, 보호자 원문과 생성 문장이 애플리케이션 로그나 CI 로그에 남지 않는다.

## 2. 전체 아키텍처

```text
보호자 요청
    |
    v
Spring API ── 트랜잭션 1: 주간 기록 저장
    |
    v
규칙 엔진 ── 최대 3개 선택 + 안전한 템플릿 생성
    |
    v
question_cache(LLM_PENDING, template body, generation_id)
    |
    +── 즉시 조회 가능: 준비 카드/치료사 요약은 템플릿 사용
    |
    `── AFTER_COMMIT 이벤트 → 단일 비동기 worker
                                  |
                                  v
                         LlmClient(OpenAI 호환)
                                  |
                      Cloudflare Access/Tunnel
                                  |
                                  v
                         Ollama + Qwen3 4B
                                  |
                         검증 통과 / 전체 폴백
                                  |
                                  v
                     generation_id가 같은 캐시만 갱신
```

판정, 감지와 질문 선택은 계속 `backend/engine`만 담당한다. LLM은 이미 만들어진 템플릿의 뜻을 바꾸지 않고 자연스러운 보호자 질문으로 다듬는 역할만 가진다.

## 3. Spring BE 설계

### 3.1 패키지와 책임

`backend/api/src/main/java/nextvisit/api/llm/` 아래에 다음 경계를 둔다.

| 구성요소 | 책임 |
| --- | --- |
| `LlmClient` | 특정 공급자와 무관한 질문 다듬기 포트 |
| `OpenAiCompatibleLlmClient` | `/v1/chat/completions` 요청, 인증 헤더, 응답 역직렬화 |
| `QuestionRewritePrompt` | 시스템 지침과 JSON 입력 작성. `/no_think`를 포함해 사고 과정 출력을 막음 |
| `QuestionOutputGuard` | 전체 응답의 구조·문장·금지 표현·숫자 보존 검사 |
| `QuestionGenerationDispatcher` | AFTER_COMMIT 작업을 bounded executor에 제출하고 제출 실패를 기록 |
| `QuestionGenerationCoordinator` | 최대 세 번 호출, 전체 성공 또는 전체 폴백, 최신 작업 확인 |
| `LlmProperties` / `LlmConfiguration` | 환경변수, HTTP timeout, executor와 조건부 빈 구성 |

`QuestionService`는 계속 엔진 결과를 캐시 형태로 바꾸는 진입점이다. LLM이 활성화됐고 질문이 하나 이상이면 템플릿 본문을 `LLM_PENDING`으로 저장하고 이벤트를 발행한다. 비활성화됐거나 질문이 없으면 기존처럼 `READY`로 끝낸다.

### 3.2 설정

```yaml
nextvisit:
  llm:
    enabled: ${NEXTVISIT_LLM_ENABLED:false}
    base-url: ${NEXTVISIT_LLM_BASE_URL:http://localhost:11434/v1}
    model: ${NEXTVISIT_LLM_MODEL:qwen3:4b-q8_0}
    api-key: ${NEXTVISIT_LLM_API_KEY:ollama}
    cf-access-client-id: ${NEXTVISIT_LLM_CF_ACCESS_CLIENT_ID:}
    cf-access-client-secret: ${NEXTVISIT_LLM_CF_ACCESS_CLIENT_SECRET:}
    connect-timeout: ${NEXTVISIT_LLM_CONNECT_TIMEOUT:3s}
    read-timeout: ${NEXTVISIT_LLM_READ_TIMEOUT:45s}
    max-attempts: ${NEXTVISIT_LLM_MAX_ATTEMPTS:3}
    max-output-tokens: ${NEXTVISIT_LLM_MAX_OUTPUT_TOKENS:512}
```

`enabled=false`가 기본값이므로 기존 테스트와 로컬 API 실행은 Ollama를 요구하지 않는다. Cloudflare Access 헤더는 두 값이 모두 있을 때만 붙인다. API 키는 OpenAI 호환 서버용 `Authorization: Bearer`에 사용하고 로컬 Ollama에서는 값이 무시된다.

### 3.3 캐시 상태와 스키마

PostgreSQL과 H2의 V2 Flyway migration으로 `question_cache.generation_id uuid null`을 추가한다.

| 상태 | 본문 | 의미 |
| --- | --- | --- |
| `READY` | 전부 `TEMPLATE` | LLM 비활성, 질문 없음, 또는 기존 템플릿 전용 상태 |
| `LLM_PENDING` | 전부 `TEMPLATE` | 비동기 작업 대기·실행 중 |
| `LLM_DONE` | 전부 `LLM` | 응답 전체가 검증을 통과함 |
| `LLM_FAILED` | 전부 `TEMPLATE` | 호출 또는 검증이 최종 실패함 |

캐시 갱신마다 새 UUID를 `generation_id`에 기록한다. 비동기 완료 트랜잭션은 `(case_id, generation_id)`가 여전히 일치할 때만 본문과 상태를 갱신한다. 같은 주차 재제출이나 연속 요청에서 과거 응답이 최신 질문을 덮는 일을 막는다.

### 3.4 트랜잭션과 비동기 순서

1. `SnapshotService.saveWeekly`가 주간 기록을 독립 트랜잭션으로 먼저 커밋한다.
2. 컨트롤러가 호출하는 `QuestionService.refresh`가 엔진을 실행하고 템플릿 캐시를 별도 트랜잭션으로 커밋한다.
3. `QuestionGenerationRequested`는 `AFTER_COMMIT`에서만 executor에 제출된다. 롤백된 캐시에 대한 LLM 호출은 없다.
4. 준비 카드와 치료사 요약은 `LLM_PENDING` 중에도 템플릿 본문을 그대로 읽는다.
5. worker는 외부 호출을 DB 트랜잭션 밖에서 수행한다.
6. 결과 저장 때 짧은 새 트랜잭션을 열어 최신 `generation_id`를 비교한 뒤 성공 또는 실패 상태를 기록한다.

`DemoService`처럼 더 큰 트랜잭션 안에서 `refresh`가 호출되면 이벤트는 가장 바깥 트랜잭션이 커밋된 뒤 실행된다. 프로세스 재시작으로 메모리 작업이 사라져도 템플릿 본문은 남는다. 별도 내구성 큐와 재시작 복구는 현재 호출량과 폴백 정책상 넣지 않는다.

### 3.5 실행 제한

- executor worker 수 1, queue 크기 32로 GPU 동시 추론을 제한한다.
- queue 제출 실패는 HTTP 요청을 실패시키지 않고 해당 generation을 `LLM_FAILED`로 바꾼다.
- 같은 generation의 최종 상태 갱신은 한 번만 허용한다.
- 애플리케이션 종료 시 새 작업을 받지 않고 진행 중 작업의 짧은 종료 유예만 둔다. 유예 뒤 중단돼도 템플릿은 보존된다.

## 4. LLM 요청 계약과 가드레일

### 4.1 요청

최대 세 문장을 한 번에 보내 호출 수를 줄인다. 사용자 입력 원문, 케이스 ID, 진단, 자유 기록은 보내지 않는다.

```json
{
  "model": "qwen3:4b-q8_0",
  "stream": false,
  "temperature": 0.1,
  "seed": 0,
  "max_tokens": 512,
  "response_format": {"type": "json_object"},
  "messages": [
    {"role": "system", "content": "규칙과 JSON 출력 계약 /no_think"},
    {"role": "user", "content": "{\"questions\":[{\"rank\":1,\"templateSentence\":\"...\"}]}"}
  ]
}
```

응답 content 계약은 다음 하나다.

```json
{"questions":[{"rank":1,"sentence":"... ?"}]}
```

Ollama의 OpenAI 호환 API가 지원하는 JSON mode와 고정 seed를 사용한다. 프롬프트는 새 사실·판정·조언·진단·운동·치료 표현 추가를 금지하고, 템플릿의 항목·기간·방향을 그대로 보존하도록 지시한다. 코드 검증이 마지막 방어선이며 프롬프트 성공을 안전성의 근거로 삼지 않는다.

### 4.2 전체 응답 검증

다음 중 하나라도 실패하면 응답 전체를 거부한다.

1. content가 정확히 하나의 JSON 객체이며 예상 필드만 갖는다.
2. 질문 수와 rank 집합이 입력과 정확히 같다. 중복·누락·추가 rank가 없다.
3. 각 문장은 trim 뒤 1~200자이며 물음표 하나로 끝난다.
4. NFC 정규화 뒤 `Templates.isQuestion`과 확장된 금지 표현 검사를 통과한다.
5. 입력 템플릿에서 추출한 모든 아라비아 숫자 토큰이 출력에도 같은 횟수로 있고 새 숫자가 생기지 않는다.
6. 마크다운, 코드 블록, 앞뒤 설명, 진단·처방·행동 지시가 없다.

금지 목록은 기존 변화 판정 계열 19개에 `재활`, `치료`, `낙상`, `점수`, `처방`, `운동`, `진단`, `기능검사`, `병원에 가`, `받으셔야`, `하셔야`를 추가한다. 과차단은 허용한다. 거부된 출력은 사용자에게 노출되지 않고 안전한 템플릿으로 닫히기 때문이다. 기존 엔진 템플릿 전수 테스트가 새 목록과 충돌하지 않는지도 고정한다.

### 4.3 재시도와 폴백

- 최초 호출과 재생성 두 번, 총 세 번을 시도한다.
- 검증 실패 시 다음 프롬프트에는 실패한 규칙 이름만 추가한다. 거부된 문장 자체는 로그나 다음 요청에 복사하지 않는다.
- timeout, 연결 실패, 4xx/5xx, 빈 content, JSON 오류와 검증 실패는 같은 실패 경로로 모은다.
- 세 번 안에 전체 응답이 통과하면 모든 질문의 `sentence/source`를 `LLM`으로 바꾼다.
- 끝까지 실패하면 LLM 출력을 전부 버리고 모든 문장을 `templateSentence/TEMPLATE`로 되돌린다.

## 5. Docker와 모델 수명주기

### 5.1 파일 구조

```text
infra/llm/
  Dockerfile
  compose.yml
  compose.gpu.yml
  compose.tunnel.yml
  .env.example
  scripts/
    wait-for-ollama.sh
    ensure-model.sh
    smoke-openai.sh
    verify-host.sh
  README.md
```

### 5.2 서비스

- `ollama`: `infra/llm/Dockerfile`로 만든 고정 버전 서버. `/root/.ollama`를 named volume에 두고 `OLLAMA_CONTEXT_LENGTH=2048`로 VRAM 사용 범위를 고정한다.
- `model-init`: 서버 health 뒤 `OLLAMA_MODEL`이 없을 때만 pull하는 일회성 서비스다. 재배포 때 모델을 다시 받지 않는다.
- `cloudflared`: 운영 override에만 있으며 `http://ollama:11434`로 전달한다.

모델은 4.4GB이므로 이미지에 포함하지 않는다. CI 이미지 빌드와 모델 다운로드를 분리해 빌드 캐시와 배포 시간을 안정시킨다.

### 5.3 CPU와 GPU

기본 `compose.yml`은 CPU이며 macOS에서 `127.0.0.1:11434`로만 bind한다. Ubuntu에서는 `compose.gpu.yml`을 함께 사용하고 NVIDIA Container Toolkit이 제공하는 GPU device reservation을 켠다. `verify-host.sh`는 Docker, Compose, `nvidia-smi`, NVIDIA Container Toolkit과 디스크 여유를 읽기 전용으로 검사한다.

운영 override는 호스트 포트를 공개하지 않는다. Ollama와 `cloudflared`만 내부 네트워크에서 통신한다.

## 6. 네트워크·비밀·로그

Cloudflare Tunnel은 인바운드 포트포워딩 없이 외부로 연결한다. Access application에는 서비스 토큰 정책을 적용하고 Spring BE만 `CF-Access-Client-Id`와 `CF-Access-Client-Secret`을 보낸다.

| 비밀 | 저장 위치 |
| --- | --- |
| Tunnel token | Ubuntu `/etc/nextvisit/llm.env` |
| Cloudflare Access client ID/secret | Spring API 배포 환경 변수 |
| 호스팅 API key(사용 시) | Spring API 배포 환경 변수 |

`.env.example`에는 이름과 비밀이 아닌 기본값만 둔다. 실제 `.env`, 모델 볼륨과 runner 자격증명은 Git에 넣지 않는다.

애플리케이션 로그는 모델명, generation ID, 소요 시간, 시도 횟수, 결과 코드와 폴백 분류만 남긴다. 프롬프트, 응답 content, 보호자 원문, 생성 문장과 인증 헤더는 남기지 않는다. 케이스 ID도 외부 호출 로그에는 쓰지 않는다.

## 7. CI/CD

### 7.1 CI

`.github/workflows/llm-ci.yml`은 `feat/llm-server`, `main` push와 모든 PR에서 관련 경로가 바뀔 때 실행한다.

1. Java 21로 `backend/gradlew test`.
2. `infra/llm/Dockerfile` build.
3. CPU·GPU·Tunnel Compose 각각 `docker compose config`.
4. 셸 스크립트 정적 검사와 가짜 OpenAI 서버 기반 BE 테스트.

GitHub-hosted runner에서는 4.4GB 모델을 pull하거나 실제 추론하지 않는다.

### 7.2 배포

`.github/workflows/llm-deploy.yml`은 `main`의 LLM 관련 변경과 수동 실행을 받는다. 실제 배포 job에는 다음 조건이 모두 필요하다.

- `vars.LLM_DEPLOY_ENABLED == 'true'`
- `push`라면 `main`, 수동 실행이면 선택한 `main` commit
- runner labels가 `[self-hosted, linux, llm]`
- GitHub environment가 `llm-laptop`

job 권한은 `contents: read`만 허용한다. PR 이벤트는 이 workflow를 실행하지 않는다. `concurrency: llm-laptop-deploy`로 동시에 하나만 배포하고 오래된 대기 배포는 취소한다.

배포 순서는 checkout → host 검증 → 환경 파일 존재·권한 확인 → 이미지 build → Compose 기동 → 모델 존재 확인 → OpenAI 호환 smoke다. model volume을 prune하지 않는다. smoke 실패는 workflow를 실패시키고 로그에는 응답 본문을 출력하지 않는다.

노트북이 준비되기 전에는 `LLM_DEPLOY_ENABLED`를 만들지 않거나 `false`로 둔다. 따라서 자체 runner job이 무기한 queue에 남거나 나중에 예상치 않게 실행되지 않는다.

### 7.3 자체 호스팅 runner

runner는 비공개인 이 저장소 전용으로 등록하고 Linux 서비스로 실행한다. 배포 전용 label을 요구하며 다른 저장소나 조직 전체에 공유하지 않는다. OS·Docker·NVIDIA 업데이트와 runner 상태 확인은 운영자 책임이다.

## 8. 장애 처리표

| 장애 | 사용자 응답 | 캐시 결과 | 운영 신호 |
| --- | --- | --- | --- |
| LLM 비활성 | 즉시 정상 | `READY`, 템플릿 | debug 1건 |
| 노트북/Tunnel 다운 | 즉시 정상 | 최종 `LLM_FAILED`, 템플릿 | 원인 분류 warn |
| HTTP timeout/5xx | 즉시 정상 | 세 번 뒤 `LLM_FAILED` | 시도 수·소요 시간 |
| 잘못된 JSON/금지 표현 | 즉시 정상 | 세 번 뒤 `LLM_FAILED` | 검증 규칙 이름 |
| queue 포화 | 즉시 정상 | `LLM_FAILED`, 템플릿 | `QUEUE_REJECTED` |
| 늦은 과거 응답 | 즉시 정상 | 최신 캐시 유지 | `STALE_RESULT` debug |
| API 재시작 | 즉시 정상 | `LLM_PENDING` 본문의 템플릿 유지 | 다음 기록 때 재시도 |

기존 `/health`는 DB 상태만으로 성공 여부를 정한다. LLM 장애 때문에 제품 전체가 503이 되지 않는다. 노트북은 Tunnel 경유 smoke URL과 배포 스크립트로 따로 감시한다.

## 9. 테스트 전략

### 9.1 단위 테스트

- 설정 기본값과 조건부 client 생성.
- 프롬프트에 입력 템플릿만 포함되고 자유 기록·ID가 들어가지 않음.
- OpenAI content JSON 파싱과 rank 재정렬.
- 빈 값, 추가 필드, rank 누락·중복, 여러 물음표, 200자 초과, 마크다운 거부.
- NFC/NFD 금지 표현, 축약형, 새 규제 금지 표현 거부.
- 숫자 누락·추가·중복 변경 거부.

### 9.2 통합 테스트

JDK 테스트 HTTP 서버 또는 Spring의 mock request factory로 외부 네트워크 없이 다음을 검증한다.

- 첫 호출 성공 → 전부 `LLM_DONE/LLM`.
- 두 번 거부 후 세 번째 성공.
- 세 번 거부, timeout, 4xx/5xx → 전부 템플릿 폴백.
- 일부 질문만 안전한 batch도 전체 폴백.
- Cloudflare와 Bearer 헤더가 설정됐을 때만 전송됨.
- 최신 generation 성공 뒤 도착한 과거 generation이 캐시를 바꾸지 못함.
- 주간 저장 응답이 느린 가짜 LLM을 기다리지 않음.
- 데모 트랜잭션 커밋 전 외부 호출이 시작되지 않음.

기존 엔진 112개와 API 59개 테스트는 모두 유지한다. 데모의 템플릿 세 문장은 LLM 비활성 기본 테스트에서 글자 단위로 계속 고정한다.

### 9.3 컨테이너 검증

- `docker build` 성공.
- 세 Compose 조합의 `docker compose config` 성공.
- CPU Ollama health와 model-init의 멱등성.
- 실제 모델을 받은 로컬 선택 smoke에서 `/v1/chat/completions`가 JSON 질문을 반환.
- smoke와 배포 로그에 생성 content와 토큰이 출력되지 않음.

Ubuntu 노트북 준비 뒤에는 `nvidia-smi`, 컨테이너 GPU 인식, `ollama ps`의 `100% GPU`, 2K 컨텍스트에서 응답 시간과 재부팅 후 자동 복구를 추가로 확인한다. 이 실기 결과는 후속 운영 기록에 남긴다.

## 10. 예상 변경 파일

- 수정: `backend/api`의 Gradle 설정, `application.yml`, 질문 캐시·서비스와 관련 테스트.
- 생성: `backend/api/.../llm/`의 client, prompt, guard, dispatcher, coordinator와 테스트.
- 생성: PostgreSQL/H2 V2 migration.
- 수정: 엔진 `Templates` 금지 표현과 전수 테스트.
- 생성: `infra/llm/` Docker·Compose·스크립트·운영 문서.
- 생성: `.github/workflows/llm-ci.yml`, `.github/workflows/llm-deploy.yml`.
- 수정: 최상위 README와 `backend/README.md`의 실행·구현 상태.

## 11. 범위 밖

- 자유 기록을 새 구조화 데이터로 바꾸는 것.
- 자유 기록의 의미 기반 반복 패턴 감지.
- 음성 인식 또는 음성 텍스트 정제.
- durable queue, 재시작 뒤 작업 재개, 주기적 자동 재처리.
- EC2의 API·PostgreSQL·Caddy 배포 파이프라인.
- Ubuntu/NVIDIA가 준비되기 전 실 GPU 성능 수치 확정.
- 모델 A/B 평가와 치료사 블라인드 평가.

## 12. 구현·인수 순서

1. TDD로 금지 표현과 출력 guard를 확장한다.
2. TDD로 공급자 독립 client와 OpenAI 호환 adapter를 만든다.
3. TDD로 캐시 generation과 비동기 전체 성공/폴백 흐름을 연결한다.
4. CPU Docker·Compose·model-init·smoke를 만든다.
5. CI와 비활성 기본 배포 workflow를 만든다.
6. 문서와 전체 테스트를 갱신한다.
7. macOS에서 Java 전체 테스트, Docker build·Compose·CPU smoke를 검증한다.
8. Ubuntu 준비 뒤 문서의 후속 체크포인트를 실행하고 `LLM_DEPLOY_ENABLED=true`로 전환한다.

이번 작업은 7번까지 통과하면 코드 기준 완료다. 8번은 하드웨어 환경이 준비돼야 시작하는 명시적 후속 검증이다.

## 13. 근거 문서

- Ollama OpenAI 호환 API: <https://docs.ollama.com/api/openai-compatibility>
- Ollama Docker와 NVIDIA Container Toolkit: <https://docs.ollama.com/docker>
- Ollama context 길이와 GPU 적재 확인: <https://docs.ollama.com/context-length>
- Qwen3 4B Q8_0 모델 태그: <https://ollama.com/library/qwen3%3A4b-q8_0>
- Cloudflare Tunnel 설치·컨테이너 실행: <https://developers.cloudflare.com/tunnel/setup/>
- GitHub 자체 호스팅 runner: <https://docs.github.com/en/actions/concepts/runners/self-hosted-runners>
- GitHub 자체 호스팅 runner 보안: <https://docs.github.com/en/actions/concepts/security/compromised-runners>
