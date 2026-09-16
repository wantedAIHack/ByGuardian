# LLM 활성화 실패 조사 (2026-09-17)

운영에 `NEXTVISIT_LLM_ENABLED=true`가 켜져 있는데도 질문 생성이 매번
`attempts=3 elapsedMs=135025 code=TIMEOUT`으로 끝나고 템플릿 문장으로 폴백되는
문제를 조사했다. **이 문서는 조사 기록이며 운영 호스트에는 아무것도 적용하지
않았다.** 컨테이너 기동/정지/재생성, DB 쓰기, `/home/nextvisit-runner` 수정은
하지 않았고 Ollama HTTP API 읽기 호출만 사용했다.

## 1. 운영이 실제로 보내는 요청

`backend/api/src/main/java/nextvisit/api/llm/OpenAiCompatibleLlmClient.java`가
만드는 요청은 다음과 같다. 값의 출처는 `LlmProperties`(= `application.yml`의
`NEXTVISIT_LLM_*` 환경변수)와 `QuestionRewritePrompt`이다.

- 엔드포인트: `POST {NEXTVISIT_LLM_BASE_URL}/chat/completions`
  (운영 컨테이너 값은 `http://ollama:11434/v1` → `http://ollama:11434/v1/chat/completions`)
- 헤더: `Content-Type: application/json`, `Accept: application/json`,
  `Authorization: Bearer <NEXTVISIT_LLM_API_KEY>`
  (CF Access ID/Secret은 운영 API 컨테이너에 비어 있어 붙지 않는다)
- 본문(필드 순서는 `Request` 레코드 순서 그대로)

```json
{
  "model": "qwen3:4b-q4_K_M",
  "stream": false,
  "temperature": 0.1,
  "seed": 0,
  "max_tokens": 3000,
  "response_format": { "type": "json_object" },
  "messages": [
    { "role": "system", "content": "<QuestionRewritePrompt.SYSTEM, 553자, 첫 줄이 /no_think>" },
    { "role": "user",   "content": "{\"questions\":[{\"rank\":1,\"templateSentence\":\"...\"}, ...]}" }
  ]
}
```

- 타임아웃: connect `3s`, read `45s`. read timeout은
  `JdkClientHttpRequestFactory.setReadTimeout`으로 설정되며 `stream:false`이므로
  **요청 1회의 전체 예산**과 같다.
- 재시도: `QuestionGenerationCoordinator`가 `maxAttempts=3`까지 반복한다.
  `LlmClientException`이면 그대로 재시도하고, 검증기 위반이면 위반 규칙명을
  system 프롬프트에 덧붙여 재시도한다. 3회를 모두 쓰면 `markFailed` →
  템플릿 폴백.
- 응답 해석: `choices[0].message.content`가 **문자열이면서 비어 있지 않을 때만**
  성공이다. 없거나 공백이면 `EMPTY_CONTENT`, JSON 파싱 실패면
  `INVALID_RESPONSE`, 읽기 타임아웃이면 `TIMEOUT`. `message.reasoning`은
  코드가 아예 읽지 않는다.

즉 `45s × 3 = 135s` 후 `code=TIMEOUT`이라는 로그는 "세 번 모두 45초 예산을
넘겼다"는 뜻이다.

## 2. 재현

호스트(`milo`, sudo 없음)에서 위와 **같은 모양의** 요청을 그대로 보냈다.
user 메시지는 운영 시드와 같은 형태의 세 질문(`rank` 1~3, 템플릿 문장)으로
만들었고 `prompt_tokens=433`이 나왔다.

조사 도중(호스트 시각 16:49:35) 운영자가 Ollama 컨테이너를 재생성해
`127.0.0.1:11434` 공개 포트가 사라졌다(= `compose.tunnel.yml`의 의도된 상태).
그 뒤 호출은 브리지 IP `172.18.0.3:11434`로 보냈다. 같은 재생성으로
`OLLAMA_CONTEXT_LENGTH`가 **8192 → 2048**로 바뀌었다(3절 참고). 아래 측정은
어느 컨텍스트에서 잰 값인지 구분해 적었다.

`OLLAMA_CONTEXT_LENGTH=8192`, `qwen3:4b-q4_K_M`, 8회:

| 회차 | 상태 | 초 | finish | content 길이 | reasoning 길이 | completion 토큰 | ollama ps |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 (콜드) | 200 | 65.03 | stop | 226 | 5190 | 1790 | 4.0 GB, 100% GPU, ctx 8192 |
| 2 | 200 | 43.79 | stop | 226 | 3779 | 1259 | 100% GPU |
| 3 | 200 | 41.53 | stop | 226 | 3779 | 1259 | 100% GPU |
| 4 (콜드) | 200 | 67.41 | stop | 226 | 5190 | 1790 | 100% GPU |
| 5 | 200 | 44.34 | stop | 226 | 3779 | 1259 | 100% GPU |
| 6 | 200 | 41.87 | stop | 226 | 3779 | 1259 | 100% GPU |
| 7 | 200 | 41.96 | stop | 226 | 3779 | 1259 | 100% GPU |
| 8 | 200 | 42.00 | stop | 226 | 3779 | 1259 | 100% GPU |

- HTTP는 8/8 모두 200이고 `content`도 8/8 비어 있지 않다. **HTTP 오류도
  빈 `content`도 아니다. 순수하게 시간이 모자란다.**
- p50 42.9초, 워밍 41.5~44.3초, 콜드 65.0~67.4초. 예산은 45초다.
- 운영은 질문 생성이 드물어 `keep_alive` 5분이 대부분 만료된 상태다. 조사 시작
  시점의 `ollama ps`도 비어 있었다. 그래서 1회차는 항상 콜드(65~67초)이고,
  이어지는 워밍 호출도 41.5~44.3초라 45초와의 여유가 1초 미만인 구간이 있다.

이때 돌아온 `content`는 검증기를 **통과하는** 값이다(허용된 표면 변환
`"입니다. "→"인데 "`, `"습니다. "→"는데 "`만 쓰였고 숫자·항목·기간이 보존됨).
모델의 출력 품질이 아니라 지연이 문제다.

### 초기 단서(빈 `content` + `reasoning`)의 검증 결과

"짧은 프롬프트에서 `content`가 비고 `reasoning`에만 글이 담겼다"는 단서는
**운영 요청 모양에서는 재현되지 않았다.** 위 8회 모두 `content`가 226자로
채워졌다. 빈 `content`는 별개의 조건에서만 나왔다: `qwen3:4b-q8_0`에서 사고
토큰이 `max_tokens=3000`을 먹어 `finish_reason=length`가 되면 `content`가 0자가
된다(4절). 즉 `reasoning`은 **원인이 아니라 지연의 형태**다 — 226자를 쓰기 전에
3779~5190자를 사고에 쓴다.

### 사고(thinking) 비활성화 시도

| 방법 | 결과 |
| --- | --- |
| system 첫 줄 `/no_think` (현재 프롬프트에 이미 있음) | 무효. reasoning 3779자 그대로 |
| `"chat_template_kwargs": {"enable_thinking": false}` 추가 | **무효.** 3회 모두 41.32/41.55/41.66초, reasoning 3779자, completion 1259토큰으로 기준선과 바이트 단위까지 동일 |

Ollama 0.33.3의 OpenAI 호환 엔드포인트는 이 두 지시를 모두 무시한다(실측).

## 3. 조사 중 발견한 운영 드리프트 (별개 문제)

호스트 시각 16:49:35에 Ollama 컨테이너가 재생성되면서
`OLLAMA_CONTEXT_LENGTH`가 **2048**이 되었다. 저장소의
`infra/llm/compose.yml` 기본값과 `infra/llm/.env.example`은 둘 다 8192이므로
이건 저장소와 어긋난 상태다. 컨테이너 라벨상 생성 위치는
`/home/nextvisit-runner/ByGuardian/infra/llm/`이고 그 디렉터리는 sudo 없이 읽을
수 없어, 오래된 체크아웃인지 `.env`가 2048인지는 확인하지 못했다.

이 상태에서 같은 요청을 4회 재보니:

| ctx | 회차 | 초 | finish | content 길이 | completion 토큰 |
| --- | --- | --- | --- | --- | --- |
| 2048 | 1 | 68.94 | stop | 194 | 1964 |
| 2048 | 2 | 65.45 | stop | 194 | 1964 |
| 2048 | 3 | 66.17 | stop | 194 | 1964 |
| 2048 | 4 | 66.38 | stop | 194 | 1964 |

느려질 뿐 아니라 **스키마가 깨진다**. 돌아온 JSON의 루트 필드가
`normalized_sentences`라서 `QuestionOutputGuard`의 `ROOT_FIELDS`에서 거부된다.
즉 지금 이 순간의 운영은 타임아웃을 고쳐도 검증기 거부로 실패한다.

1절의 TIMEOUT 로그(호스트 시각 2026-09-16 15:22, 15:24)는 재생성 **이전**,
컨텍스트가 8192이던 컨테이너에서 난 것이므로 근본 원인 판정에는 영향이 없다.
이건 나중에 얹힌 두 번째 결함이다.

## 4. 후보 비교

모두 같은 프롬프트, 같은 요청 모양, 같은 GPU(GTX 1060 6 GiB). 최소 3회씩.

| 모델 | ctx | 회수 | p50(초) | max(초) | content 길이 | GPU/CPU 분할 (ollama ps) | 검증기 통과 | 45초 예산 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen3:4b-q4_K_M` | 8192 | 8 | 42.9 | 67.4 | 226 | 4.0 GB, **100% GPU** | O (허용 표면 변환 적용) | X (콜드 초과, 워밍도 여유 1초 미만) |
| `qwen3:4b-q4_K_M` | 2048 | 4 | 66.3 | 68.9 | 194 | 3.0 GB, 100% GPU | X (`ROOT_FIELDS`) | X |
| `qwen3:4b-q8_0` | 8192 | 3 | 225.6 | 225.8 | 0 / 226 | 5.7 GB, **10%/90% CPU/GPU** | X (3회 중 2회 `finish=length`, content 0자 → `EMPTY_CONTENT`) | X |
| `qwen2.5:7b-instruct-q4_K_M` | 8192·2048 | 6 | 6.2 | 12.8 | 232 | 4.6~5.0 GB, **100% GPU** | O (단, **원문 그대로** 반환 — 다듬기 0) | O |
| `qwen2.5:3b-instruct-q8_0` | 8192 | 3 | 1.7 | 6.6 | 138 | 3.8 GB, **100% GPU** | X (`SURFACE_REWRITE`: 문장 앞부분을 잘라냄) | O |

- `qwen3:4b-q8_0`은 6 GiB VRAM을 넘겨 CPU로 10% 새어 나간다(`10%/90% CPU/GPU`).
  `OWNER_CHECKLIST`의 기존 기록과 같은 현상이며 이번엔 113~226초로 더 나빴다.
- `qwen2.5:7b-instruct-q4_K_M`은 5.0 GB로 6 GiB 안에 들어와 `100% GPU`를
  유지하지만, `temperature=0.1, seed=0`에서 3회 모두 **템플릿 문장을 한 글자도
  바꾸지 않고** 되돌려줬다. 검증기는 통과하지만 `source`만 `LLM`로 바뀌고
  보호자가 보는 문장은 템플릿과 같다 — 제품 가치가 0이고 오히려 오해를 준다.
- `qwen2.5:3b-instruct-q8_0`은 빠르지만 문장 앞부분을 잘라 사실을 지운다.
  검증기가 정확히 그 이유로 막는다.

즉 **다듬기를 실제로 해내는 모델은 이미 쓰고 있는 `qwen3:4b-q4_K_M` 뿐이고,
그 모델의 정상 소요시간이 예산보다 길다.**

## 5. 근본 원인

`qwen3`은 하이브리드 추론 모델이고, Ollama 0.33.3의 OpenAI 호환 엔드포인트에서는
`/no_think`로도 `chat_template_kwargs.enable_thinking=false`로도 사고를 끌 수
없다(실측). 그래서 226자짜리 답을 내기 전에 매번 1259~1790 completion 토큰
(reasoning 3779~5190자)을 먼저 소비하고, GTX 1060에서 이 작업은 워밍 41.5~44.3초,
콜드 65~67초가 걸린다. `NEXTVISIT_LLM_READ_TIMEOUT`의 45초는 이 정상 소요시간보다
짧으므로 세 번의 시도가 모두 예산에서 잘리고 `attempts=3 elapsedMs=135025
code=TIMEOUT`이 나온다. 모델 출력이나 검증기 문제가 아니다.

## 6. 권장 조치

### 6-1. 설정만으로 고친다 (코드 변경 불필요)

운영 API 컨테이너 환경변수에 아래 한 줄을 추가한다. 이미
`application.yml`에 `read-timeout: ${NEXTVISIT_LLM_READ_TIMEOUT:45s}`로
노출돼 있어 코드 변경이 필요 없다.

```dotenv
NEXTVISIT_LLM_READ_TIMEOUT=120s
```

근거: 관측 최댓값은 콜드 67.4초다. 120초는 그 1.8배로, 콜드 로드와 호스트
지연 편차를 모두 덮는다. 이 호출은 비동기라 보호자는 저장 즉시 템플릿 질문을
보고 LLM 결과는 뒤에 반영되므로, 늘어난 예산이 대기 시간이 되지 않는다.
최악의 경우(3회 모두 실패) LLM 워커 1개가 360초 점유되지만 워커는 큐
용량 32의 단일 스레드이고 질문 생성 빈도가 낮아 문제되지 않는다.

### 6-2. 같이 되돌려야 하는 값 (3절 드리프트)

```dotenv
OLLAMA_CONTEXT_LENGTH=8192
```

Ollama 컨테이너가 8192로 뜨지 않으면 6-1만으로는 낫지 않는다(스키마가 깨져
`ROOT_FIELDS`에서 거부된다). 저장소 기본값이 이미 8192이므로, 운영 체크아웃
`/home/nextvisit-runner/ByGuardian`을 현재 `main`으로 맞추고 `.env`에
`OLLAMA_CONTEXT_LENGTH=2048`이 남아 있지 않은지 확인한 뒤 재생성해야 한다.
적용 후 `ollama ps`의 CONTEXT 열이 `8192`, PROCESSOR 열이 `100% GPU`인지
확인한다.

### 6-3. 선택 사항 (이번 조사 범위 밖)

콜드 로드를 없애려면 Ollama에 `OLLAMA_KEEP_ALIVE=-1`을 주어 모델을 상주시키면
워밍 구간(41~44초)만 남는다. 다만 4.0 GB VRAM을 계속 점유한다. 6-1의 120초
예산이면 콜드여도 통과하므로 필수는 아니다.

### 하지 않기로 한 것

- **모델 교체**: 4절대로 `qwen2.5:7b`는 빠르지만 문장을 전혀 다듬지 않고,
  `qwen2.5:3b`는 검증기에서 막힌다. 다듬기를 해내는 건 현재 모델뿐이다.
- **코드 변경**: 요청 옵션·파싱·재시도 정책 어느 쪽도 고칠 필요가 없었다.
  `/no_think`는 무효지만 해롭지도 않아, 프롬프트를 건드리면
  `QuestionRewritePromptTest`만 깨지고 얻는 게 없다.
- **`max_tokens` 축소**: 사고가 1259~1790 토큰을 쓰므로 줄이면
  `finish_reason=length` → `content` 0자 → `EMPTY_CONTENT`가 된다
  (`qwen3:4b-q8_0`에서 실제로 관측).

## 7. 안전 계층과 테스트

`QuestionOutputGuard`는 LLM 출력이 NFC 정규화한 템플릿과 정확히 같거나,
열거된 두 표면 변환(`"습니다. "→"는데 "`, `"입니다. "→"인데 "`) 중 하나만
적용된 경우에만 통과시킨다. 금칙어, 마크다운, 지시문, 숫자 토큰, rank 집합,
물음표 개수도 함께 막는다. 건강 점수·진단·운동 권유·호전/악화 판단은 이
구조상 원천적으로 통과할 수 없으며, 이번 조사에서 이 규칙을 하나도 약화시키지
않았다.

테스트 실행 결과 (`cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :api:test --rerun-tasks`):

- `:api:test` 147개 전부 통과 (실패 0, 에러 0, 스킵 0)
- `:engine:test` 123개 전부 통과 (`ReadmeConformanceTest` 포함)

모델 이름을 고정하는 테스트:

- `LlmConfigurationTest.defaultsAreSafeAndDoNotCreateAWorker`가 **기본값**
  `model=qwen3:4b-q4_K_M`, `readTimeout=45s`, `maxOutputTokens=3000`,
  `maxAttempts=3`을 단언한다. 환경변수로 값을 바꾸는 건 이 테스트와 무관하지만,
  `application.yml`/`LlmProperties`의 **기본값**을 바꾸면 이 테스트를 같이
  고쳐야 한다.
- `OpenAiCompatibleLlmClientTest`, `QuestionGenerationCoordinatorTest`가 쓰는
  `qwen3:4b-q8_0`은 요청 본문·로그 문자열을 확인하는 픽스처 값일 뿐이라
  모델 선택을 구속하지 않는다.
- `QuestionRewritePromptTest`는 system 메시지에 `/no_think`가 들어 있는지
  단언한다. (무효임이 확인됐지만) 프롬프트에서 빼려면 이 테스트를 고쳐야 한다.
- 검증기 테스트(`QuestionOutputGuardTest`)는 모델 이름을 전혀 참조하지 않는다.

정리하면 **환경변수만 바꾸는 6-1/6-2 조치는 어떤 테스트도 건드리지 않는다.**

## 8. 확인하지 못한 것

- 운영에 실제로 적용해 본 결과. 이 과제는 적용 금지라 6-1/6-2는 **미검증
  권고**다. 적용 후 `code=SUCCESS`와 준비 카드의 `source=LLM`을 확인해야 한다.
- 재현에 쓴 user 메시지는 운영 시드와 같은 형태로 직접 만든 세 질문이다
  (`prompt_tokens=433`). 실제 운영 케이스의 토큰 수가 더 크면 소요시간도
  늘어난다.
- 2026-09-14~15 로그는 같은 모델·같은 8192 컨텍스트에서
  `attempts=1 elapsedMs=23292~27472 code=SUCCESS`였는데, 오늘 같은 조건에서
  41~44초가 나왔다. **약 1.7배 느려진 이유를 특정하지 못했다.** 생성 중
  GPU는 98% 사용률, 1468~1556 MHz, 73~77 W, 온도 65→80 ℃였고
  `nvidia-smi`의 HW/SW throttle 플래그는 모두 Not Active였다. 입력에 따른
  사고 길이 차이일 수도, 09-16 14:37 재부팅 이후의 호스트 상태 변화일 수도
  있다. 어느 쪽이든 6-1의 120초 예산은 두 경우를 모두 덮는다.
- `OLLAMA_CONTEXT_LENGTH=2048` 드리프트의 출처.
  `/home/nextvisit-runner/ByGuardian/infra/llm/`를 sudo 없이 읽을 수 없어
  오래된 체크아웃인지 `.env` 값인지 확인하지 못했다.
- Ollama 네이티브 `/api/chat`의 `think: false`는 시험하지 않았다. 백엔드가
  `/v1` 경로만 쓰므로 범위 밖으로 두었다.
- `qwen2.5:7b-instruct`가 다른 온도/시드에서 표면 변환을 해낼지는 확인하지
  않았다(`temperature=0.1, seed=0` 고정 3회만 관측).
