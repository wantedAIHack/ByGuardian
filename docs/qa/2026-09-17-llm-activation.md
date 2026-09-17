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

조사 도중(호스트 시각 16:49:35) 이 세션 밖에서 Ollama 컨테이너가 재생성되어
`127.0.0.1:11434` 공개 포트가 사라졌다(= `compose.tunnel.yml`의 의도된 상태).
그 뒤 호출은 브리지 IP(`172.18.0.3`, 17:07 재시작 뒤 `172.18.0.4`)의
`:11434`로 보냈다. 같은 재생성으로 `OLLAMA_CONTEXT_LENGTH`가 **8192 → 2048**로
바뀌었다(3절 참고). 아래 측정은 어느 컨텍스트에서 잰 값인지 구분해 적었다.

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

조사 도중 운영 호스트의 상태가 여러 번 바뀌었다. **이 변경들은 이 조사
세션 밖에서 일어났다.** 나는 컨테이너 생명주기 명령을 한 번도 실행하지
않았고, DB에 접근하지 않았으며, `/home/nextvisit-runner` 아래에 쓰지 않았고,
`sudo`를 쓰지 않았다. **누가 또는 무엇이 이 변경을 했는지는 내 위치에서 알 수
없으므로 특정 주체에게 돌리지 않는다.** 아래는 읽기 전용 명령으로 **직접
확인한 사실만** 적은 것이다. 시각은 모두 호스트 시각(UTC)이며, 호스트 시계는
이 문서의 날짜보다 하루 뒤처져 2026-09-16을 가리킨다.

| 시각(UTC) | 확인된 변화 | 확인 방법 |
| --- | --- | --- |
| 14:37 | 호스트 재부팅 | `last`, 컨테이너 StartedAt |
| 15:22, 15:24 | 운영 로그에 `attempts=3 elapsedMs=135025 code=TIMEOUT` 2건 | `docker logs nextvisit-demo-api-1` |
| 16:49:35 | `nextvisit-llm-ollama-1` 재생성 (kill→stop→die→destroy→rename→start) | `docker events` |
| 16:49:35 | 재생성된 컨테이너의 `OLLAMA_CONTEXT_LENGTH`가 **8192 → 2048** | `docker inspect` |
| 16:49:35 | `127.0.0.1:11434` 게시 포트 제거 (`PortBindings` 비어 있음) | `docker port`, `docker inspect` |
| 16:49:36 | `nextvisit-llm-cloudflared-1`이 **재시작 루프** 진입, 이후 계속 `Restarting (255)` | `docker ps -a` |
| 17:01:03 | `nextvisit-demo-api-1` 재생성 | `docker ps -a --format {{.CreatedAt}}` |
| 17:07:07 | `nextvisit-llm-ollama-1` 재시작(브리지 IP `172.18.0.3` → `172.18.0.4`) | `docker inspect` |
| 17:07:12 | 호스트 systemd `cloudflared` 서비스 재시작 | `systemctl show -p ActiveEnterTimestamp` |
| 17:10~22:56 사이 | `nextvisit-llm-cloudflared-1` 컨테이너가 **사라짐**(`docker ps -a` 목록에서 제거됨). 정확한 시각은 확인하지 못했다 | `docker ps -a` |

22:56 기준으로 호스트에 남아 있는 컨테이너는 `nextvisit-demo-api-1`,
`nextvisit-llm-ollama-1`, `nextvisit-demo-postgres-1` 셋뿐이다.

### 3-1. cloudflared 컨테이너 재시작 루프 (지금은 사라짐) — API는 죽은 적이 없다

`nextvisit-llm-cloudflared-1`은 16:49:36부터 `Restarting (255)` 상태를 반복했고
로그에는 매번 `Provided Tunnel token is not valid.`만 남았다. 이건
`compose.tunnel.yml`이 띄우는 **Ollama 전용 터널** 컨테이너였고, 그 터널은
`docs/superpowers/specs/2026-09-17-single-host-deployment-design.md`가 "만들지
않기로" 한 `llm.<domain>` 경로다. 즉 토큰이 없거나 무효인 게 그 설계상
당연한 상태였다.

**이 컨테이너는 22:56 확인 시점에는 이미 제거되어 `docker ps -a`에 없다.**
언제, 무엇에 의해 제거됐는지는 확인하지 못했다(이 세션은 컨테이너를 지우지
않는다). 따라서 아래 내용은 과거 기록이며, 지금 호스트에서 이 재시작 루프를
다시 볼 수는 없다.

**중요: API를 서빙하는 건 이 컨테이너가 아니라 호스트 systemd의 `cloudflared`
서비스다.** 이 컨테이너가 루프를 돌던 동안에도, 사라진 뒤에도 API는 정상이었다.
22:56 재확인:

```
ssh llm 'systemctl is-active cloudflared'                                 → active
curl -s -o /dev/null -w '%{http_code}' https://api.byguardian.site/health → 200
```

`docs/qa/2026-09-17-single-host-inventory.md`의 "Cloudflare Tunnel" 절도 같은
사실(컨테이너가 아니라 호스트 systemd 서비스)을 기록하고 있다. 읽는 사람이
"API가 내려갔다"고 오해하지 않도록 적어 둔다.

### 3-2. CORS가 한 origin만 허용하도록 바뀌었다

17:01:03의 API 재생성 이후 `nextvisit-demo-api-1`의
`NEXTVISIT_CORS_ORIGINS`는 `https://app.byguardian.site` **하나뿐**이다
(비밀값이 아니라 공개 origin이라 그대로 적는다). 아래는 이 문서를 쓰기 직전
22:56에 직접 preflight를 보내 **재확인한** 결과다:

| Origin | `OPTIONS /me` | `Access-Control-Allow-Origin` |
| --- | --- | --- |
| `https://app.byguardian.site` | **200** | `https://app.byguardian.site` |
| `https://byguardian.pages.dev` | **403** | 없음 |
| `https://app.byguardian.site.evil.invalid` | 403 | 없음 |

이건 **이전과 정확히 반대**다. 같은 저장소의 커밋 `7badde9`
(2026-09-17 01:01 KST)가 기록한 당시 실측은 `byguardian.pages.dev` → 200,
`app.byguardian.site` → 403이었다. 즉 그 사이 이 세션 밖에서 CORS 전환이
이루어졌고, 그 방향은 계획(`docs/superpowers/plans/2026-09-17-single-host-operations-completion.md:217`,
두 origin을 모두 허용)과 달리 **한 origin만** 남기는 쪽이었다. `pages.dev` →
`app` 리다이렉트가 없는 상태에서 `pages.dev`로 들어온 사용자는 이제 API 호출이
전부 막힌다. 누가 왜 그렇게 했는지는 내 위치에서 알 수 없으므로 특정 주체에게
돌리지 않는다. LLM 결함과는 무관하지만 같은 창에서 바뀐 사실이라 남긴다.

### 3-3. `OLLAMA_CONTEXT_LENGTH` 2048 드리프트

저장소의 `infra/llm/compose.yml:11`은 `"${OLLAMA_CONTEXT_LENGTH:-8192}"`이고
`infra/llm/.env.example`도 8192이므로, 라이브의 2048은 저장소와 어긋난
값이다. 컨테이너 라벨상 생성 위치는
`/home/nextvisit-runner/ByGuardian/infra/llm/`이고 그 디렉터리는 sudo 없이
읽을 수 없어, 오래된 체크아웃인지 `.env`가 2048인지는 확인하지 못했다.

이 상태에서 같은 요청을 7회 재보니(4회 + 재확인 3회):

| 회차 | 초 | finish | content 길이 | completion 토큰 | 검증기 |
| --- | --- | --- | --- | --- | --- |
| 1 | 68.94 | stop | 194 | 1964 | `ROOT_FIELDS` |
| 2 | 65.45 | stop | 194 | 1964 | `ROOT_FIELDS` |
| 3 | 66.17 | stop | 194 | 1964 | `ROOT_FIELDS` |
| 4 | 66.38 | stop | 194 | 1964 | `ROOT_FIELDS` |
| 5 | 71.44 | stop | 194 | 1964 | `ROOT_FIELDS` |
| 6 | 66.07 | stop | 194 | 1964 | `ROOT_FIELDS` |
| 7 | 66.25 | stop | 194 | 1964 | `ROOT_FIELDS` |

느려질 뿐 아니라 **스키마가 깨진다**. 돌아온 JSON의 루트 키가 `questions`가
아니라 `normalized_sentences`라서 `QuestionOutputGuard`가 `ROOT_FIELDS`에서
거부한다. 지금 이 순간의 운영은 타임아웃을 고쳐도 검증기 거부로 실패한다.

**왜 컨텍스트를 줄이면 스키마가 바뀌는가 — 가설.** `num_ctx=2048`인데
prompt 433 + completion 1964 = **2397 토큰**이라 창을 넘는다. 창을 넘으면
Ollama/llama.cpp가 가장 오래된 토큰부터 밀어내고, 그 맨 앞이 JSON 스키마를
지시하는 system 메시지다. 답을 쓰기 시작할 때쯤 지시가 창에서 사라져 모델이
키 이름을 지어낸다는 설명이다. 이 가설과 어긋나지 않는 관측 14건:

| 조건 | prompt+completion | 창 | 루트 키 |
| --- | --- | --- | --- |
| q4_K_M @ 2048 (7회) | 2397 | 2048 | `normalized_sentences` (7/7) |
| q4_K_M @ 8192 (8회) | 1692~2223 | 8192 | `questions` (8/8) |
| q8_0 @ 2048 (3회) | 1285~1773 | 2048 | `questions` (3/3) |

창을 넘긴 조건에서만 키가 바뀌었다. 다만 이건 **가설이다.** 창 초과가 일어난
조건은 모델 하나(q4_K_M)·컨텍스트 하나(2048)뿐이고 n=7이라, 다른 설명(양자화별
지시 준수 차이 등)을 배제할 만큼의 대조군이 없다. 실제로 재현·반증하려면
`num_ctx`를 단계적으로 바꿔 가며 같은 모델로 경계를 찾아야 하는데, 그건 운영
컨테이너를 다시 만들어야 해서 이 과제에서는 하지 않았다.

1절의 TIMEOUT 로그(15:22, 15:24)는 재생성 **이전**, 컨텍스트가 8192이던
컨테이너에서 난 것이므로 근본 원인 판정에는 영향이 없다. 2048은 나중에 얹힌
두 번째 결함이다.

## 4. 후보 비교 (회차별)

모두 같은 프롬프트, 같은 요청 모양, 같은 GPU(GTX 1060 6 GiB). 요약값을 믿지
않아도 되도록 **모든 회차를 그대로** 싣는다. 입력 템플릿 세 문장의 길이(코드
포인트)는 rank1=64자, rank2=43자, rank3=35자다. 출력 문장은 매 회차마다 rank별로
템플릿과 문자 단위로 비교했고, 판정은 `QuestionOutputGuard.isPermittedSurfaceRewrite`를
그대로 옮겨 계산했다.

- `IDENTICAL` = 템플릿과 한 글자도 다르지 않음 (검증기 통과, 다듬기 0)
- `PERMITTED_REWRITE` = 허용된 두 표면 변환 중 하나만 적용 (검증기 통과, 다듬기 O)
- `OTHER` = 그 밖 (검증기 거부, 차이를 diff로 표시)

### 4-1. `qwen2.5:7b-instruct-q4_K_M` — 빠르지만 다듬지 않는다

이 모델을 버리는 판단이 가장 무겁기 때문에(7배 빠르다) 회차별로 전부 보인다.
아래 4회는 content를 회차마다 따로 받아 rank별로 비교한 것이다.

| 회차 | ctx | 초 | finish | content 길이 | compl 토큰 | rank1 (템플릿 64자) | rank2 (43자) | rank3 (35자) | ollama ps |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2048 | 11.84 | stop | 232 | 125 | 출력 64자 · `IDENTICAL` | 43자 · `IDENTICAL` | 35자 · `IDENTICAL` | 4.6 GB, 100% GPU |
| 2 | 2048 | 6.00 | stop | 232 | 125 | 64자 · `IDENTICAL` | 43자 · `IDENTICAL` | 35자 · `IDENTICAL` | 4.6 GB, 100% GPU |
| 3 | 2048 | 6.01 | stop | 232 | 125 | 64자 · `IDENTICAL` | 43자 · `IDENTICAL` | 35자 · `IDENTICAL` | 4.6 GB, 100% GPU |
| 4 | 2048 | 6.02 | stop | 232 | 125 | 64자 · `IDENTICAL` | 43자 · `IDENTICAL` | 35자 · `IDENTICAL` | 4.6 GB, 100% GPU |

**12개 문장(4회 × 3 rank) 모두 입력과 길이가 같고 문자 단위 diff가 비어 있다.**
"원문 그대로 반환한다"는 건 인상이 아니라 이 12건의 측정 결과다.

**초안의 숫자 불일치를 정정한다.** 초안 표는 "6 runs"라고 적었는데 그 6회는
컨텍스트 8192에서 잰 3회(12.78 / 6.14 / 6.15초)와 2048에서 잰 3회(10.87 /
6.15 / 6.16초)를 합친 **타이밍 전용** 측정이었다. 그때는 content를 회차마다
받지 않고 컨텍스트별로 1건씩, 총 2건만 덤프해 `IDENTICAL`을 확인했다. 그래서
본문의 "3회 모두"는 어느 6회에도 대응하지 않는 잘못된 표현이었다. 정정하면:

- **문자 단위로 rank별 비교를 끝낸 건 위 표의 4회(12개 문장)다.**
- 타이밍만 있는 초안의 6회는 별개이며, 그중 content를 본 건 2건이고 둘 다
  템플릿과 같았다.
- 즉 이 모델의 총 관측은 10회, content를 확인한 건 6회(위 4회 + 초안 2건),
  rank별 diff까지 확인한 건 4회다. content 길이는 10회 중 확인한 6회 모두
  232자로 동일했다.

`temperature=0.1, seed=0`으로 고정했으므로 이 결정성은 놀랍지 않다. 다른
온도/시드에서 표면 변환을 해낼지는 확인하지 않았다(8절).

이 모델을 쓰면 검증기는 통과하지만 `source`만 `LLM`로 바뀌고 보호자가 보는
문장은 템플릿과 한 글자도 다르지 않다. 제품 가치가 0일 뿐 아니라, 로그와
API 응답이 "LLM이 다듬었다"고 말하게 되므로 정직한 템플릿 폴백보다 나쁘다.

### 4-2. `qwen2.5:3b-instruct-q8_0` — 근거 문장을 통째로 지운다

| 회차 | ctx | 초 | finish | content 길이 | compl 토큰 | rank1 | rank2 | rank3 | ollama ps |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2048 | 6.64 | stop | 138 | 58 | 64자→**22자** `OTHER` | 43자→**11자** `OTHER` | 35자→**15자** `OTHER` | 3.4 GB, 100% GPU |
| 2 | 2048 | 1.70 | stop | 138 | 58 | 64→22 `OTHER` | 43→11 `OTHER` | 35→15 `OTHER` | 3.4 GB, 100% GPU |
| 3 | 2048 | 1.70 | stop | 138 | 58 | 64→22 `OTHER` | 43→11 `OTHER` | 35→15 `OTHER` | 3.4 GB, 100% GPU |
| 4 | 2048 | 1.70 | stop | 138 | 58 | 64→22 `OTHER` | 43→11 `OTHER` | 35→15 `OTHER` | 3.4 GB, 100% GPU |

4회 모두 동일한 diff가 나왔고, 세 rank 모두 **삭제(delete) 연산 하나**뿐이다.

| rank | 지워진 부분 | 남은 부분 |
| --- | --- | --- |
| 1 | `옷 입기는 도움 조금으로 바뀌셨는데 화장실 이용하기는 3주째 그대로입니다. ` | `화장실 이용하기는 왜 안 늘고 있을까요?` |
| 2 | `일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. ` | `통증일 수 있을까요?` |
| 3 | ` 4주째 도움이 더 필요해지셨습니다.` | `식사하기는 어떻게 보시나요?` |

"사실을 지운다"는 표현은 여기서 **관측된 삭제 연산 그 자체**다 — 보호자가 본
횟수(`4주 중 3주`), 기간(`3주째`, `4주째`), 관찰 내용이 통째로 사라졌다.
`QuestionOutputGuard`가 이를 막는 근거 규칙은 `SURFACE_REWRITE`인데, 이 규칙
이름을 붙인 것은 **내 해석**이다(검증기를 직접 돌린 게 아니라 같은 판정 로직을
옮겨 계산했다). 규칙 이름이 무엇이든, 지워진 내용 자체는 diff로 확정된 사실이다.

### 4-3. `qwen3:4b-q8_0` — 컨텍스트에 따라 결과가 갈린다

컨텍스트 8192 (VRAM 초과):

| 회차 | 초 | finish | content 길이 | reasoning 길이 | compl 토큰 | ollama ps |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 112.84 | stop | 226 | 4544 | 1536 | 5.7 GB, **10%/90% CPU/GPU** |
| 2 | 225.77 | **length** | **0** | 9581 | 3000 | 5.7 GB, 10%/90% CPU/GPU |
| 3 | 225.60 | **length** | **0** | 9581 | 3000 | 5.7 GB, 10%/90% CPU/GPU |

컨텍스트 2048 (VRAM에 들어감):

| 회차 | 초 | finish | content 길이 | reasoning 길이 | compl 토큰 | rank1/2/3 | ollama ps |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 41.85 | stop | 226 | 2679 | 852 | 62/41/33자 · 셋 다 `PERMITTED_REWRITE` | 4.7 GB, 100% GPU |
| 2 | 56.43 | stop | 226 | 3991 | 1340 | 62/41/33자 · 셋 다 `PERMITTED_REWRITE` | 4.7 GB, 100% GPU |
| 3 | 54.50 | stop | 226 | 3991 | 1340 | 62/41/33자 · 셋 다 `PERMITTED_REWRITE` | 4.7 GB, 100% GPU |

8192에서는 KV 캐시가 6 GiB를 넘어 CPU로 10% 새어 나가고(`10%/90% CPU/GPU`,
5.7 GB) 113~226초가 걸리며, 3회 중 2회는 사고가 `max_tokens=3000`을 다 먹어
`finish_reason=length`, `content` 0자 → `EMPTY_CONTENT`가 된다.
2048에서는 4.7 GB로 들어와 `100% GPU`를 유지하고 올바른 표면 변환도 해내지만
41.9~56.4초로 여전히 45초 예산을 3회 중 2회 넘긴다. 어느 쪽도 채택 근거가 없다.

### 4-4. `qwen3:4b-q4_K_M` (현행) — 회차 전체

컨텍스트 8192:

| 회차 | 초 | 상태 | finish | content 길이 | reasoning 길이 | compl 토큰 | ollama ps |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 (콜드) | 65.03 | 200 | stop | 226 | 5190 | 1790 | 4.0 GB, 100% GPU |
| 2 | 43.79 | 200 | stop | 226 | 3779 | 1259 | 100% GPU |
| 3 | 41.53 | 200 | stop | 226 | 3779 | 1259 | 100% GPU |
| 4 (콜드) | 67.41 | 200 | stop | 226 | 5190 | 1790 | 100% GPU |
| 5 | 44.34 | 200 | stop | 226 | 3779 | 1259 | 100% GPU |
| 6 | 41.87 | 200 | stop | 226 | 3779 | 1259 | 100% GPU |
| 7 | 41.96 | 200 | stop | 226 | 3779 | 1259 | 100% GPU |
| 8 | 42.00 | 200 | stop | 226 | 3779 | 1259 | 100% GPU |

세 rank 모두 `PERMITTED_REWRITE`(`"입니다. "→"인데 "`, `"습니다. "→"는데 "`)로
검증기를 통과한다. 컨텍스트 2048에서의 7회는 3-3절 표에 있다.

### 4-5. 요약

| 모델 | ctx | 회수 | p50(초) | max(초) | content 길이 | GPU/CPU 분할 | 판정 | 45초 예산 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen3:4b-q4_K_M` | 8192 | 8 | 42.9 | 67.4 | 226 | 4.0 GB, 100% GPU | `PERMITTED_REWRITE` 24/24 문장 | X |
| `qwen3:4b-q4_K_M` | 2048 | 7 | 66.3 | 71.4 | 194 | 3.0 GB, 100% GPU | `ROOT_FIELDS` 7/7 | X |
| `qwen3:4b-q8_0` | 8192 | 3 | 225.6 | 225.8 | 0 / 226 | 5.7 GB, **10%/90% CPU/GPU** | `length`로 빈 content 2/3 | X |
| `qwen3:4b-q8_0` | 2048 | 3 | 54.5 | 56.4 | 226 | 4.7 GB, 100% GPU | `PERMITTED_REWRITE` 9/9 | X (3회 중 2회 초과) |
| `qwen2.5:7b-instruct-q4_K_M` | 2048 | 4 (+타이밍 6) | 6.0 | 11.8 | 232 | 4.6 GB, 100% GPU | `IDENTICAL` 12/12 — 다듬기 0 | O |
| `qwen2.5:3b-instruct-q8_0` | 2048 | 4 | 1.7 | 6.6 | 138 | 3.4 GB, 100% GPU | `OTHER` 12/12 — 근거 문장 삭제 | O |

즉 **예산 안에 들어오는 두 모델은 다듬기를 안 하거나(7b) 사실을 지우고(3b),
다듬기를 제대로 하는 세 조합은 모두 45초를 넘는다.** 그중 가장 빠르고
`100% GPU`를 유지하는 건 이미 쓰고 있는 `qwen3:4b-q4_K_M` @ 8192다.

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
NEXTVISIT_LLM_READ_TIMEOUT=180s
```

**값을 120초에서 180초로 고쳤다.** 이 문서의 초안은 "관측 최댓값 67.4초의
1.8배이므로 충분하다"고 적었는데, 그건 같은 문서가 스스로 기록한 원인 불명의
1.7배 둔화(8절)를 계산에 넣지 않은 비약이었다. 아래에 근거를 다시 세운다.

**측정된 최악값**

| 조건 | 최악 관측 | 회수 |
| --- | --- | --- |
| q4_K_M @ 8192, 워밍 | 44.3초 | 6 |
| q4_K_M @ 8192, 콜드 | **67.4초** | 2 |
| q4_K_M @ 2048, 콜드 | **71.4초** | 7회 중 최악 |

단일 호출의 측정 최악값은 **71.4초**이고, 권장 설정(컨텍스트 8192)에서의
최악값은 **67.4초**다.

**남는 여유**

| 예산 | 오늘 최악(67.4초) 대비 | 1.7배 둔화가 재발하면 |
| --- | --- | --- |
| 45초 (현행) | **부족** — 워밍 p50조차 못 넘김 | 논외 |
| 120초 | 1.78배 | 콜드 67.4 × 1.7 ≈ **115초** → 여유 5초(1.04배). 사실상 없음 |
| **180초** | 2.67배 | 115초 대비 1.57배 여유 |

**1.7배 둔화가 재발하면 어떻게 되는가.** 2026-09-14~15 운영 로그는 같은 모델,
같은 8192 컨텍스트에서 `attempts=1 elapsedMs=23292~27472 code=SUCCESS`였는데
오늘 같은 요청이 워밍 41~44초다. 원인을 특정하지 못했다(8절). 같은 크기의
둔화가 한 번 더 일어나면 워밍은 약 75초, 콜드는 약 115초가 된다. 120초로는
콜드에서 5초만 남아 오늘과 똑같은 전량 TIMEOUT으로 되돌아간다. 180초면 그
시나리오에서도 1.57배가 남는다. 그 이상(예: 300초)으로 더 키우지 않는 이유는
아래 비용 때문이다.

**비용과 그 실제 이유.** 이 호출은 비동기라 보호자는 저장 즉시 템플릿 질문을
보고 LLM 결과는 뒤에 반영된다. 따라서 늘어난 예산이 보호자 대기 시간이 되지는
않는다. 대신 실패 1건이 LLM 워커를 최대 `3 × 180 = 540초` 점유한다.
초안은 이를 "질문 생성 빈도가 낮아 문제되지 않는다"고 적었는데 그건 근거가
아니다. 정확한 이유는 **동시성이 한 호출의 지연을 늘릴 수 없다는 구조**다.
`LlmConfiguration.llmTaskExecutor`는 `corePoolSize=1`, `maxPoolSize=1`,
`queueCapacity=32`, `AbortPolicy`인 단일 스레드 executor이고
(`LlmConfigurationTest.enabledCreatesOneBoundedWorker`가 이 네 값을 단언한다),
`ollama ps`도 매 측정에서 모델 인스턴스가 하나뿐임을 보여 줬다. 즉 두 요청이
GPU에서 겹쳐 서로를 느리게 만드는 일이 없고, 위 측정값이 그대로 운영값이다.
대가는 **큐 배수**다: 큐에 쌓인 생성이 전부 실패하면 배수 시간이 최대
`대기 건수 × 540초`가 되고, 큐가 가득 찬 32건이면 4.8시간이다. 그 사이
들어오는 생성은 `AbortPolicy`로 거부된다. 실패가 연속되는 상황 자체가 이미
장애이므로 이 배수 시간은 "고쳐야 할 신호"이지 감수할 비용이 아니다.

**운영자가 볼 것.** 타임아웃은 예산일 뿐 둔화의 해결책이 아니다. 아래가
악화되면 예산을 더 키울 게 아니라 원인을 찾아야 한다.

| 볼 것 | 정상 | 조치 기준 |
| --- | --- | --- |
| `code=SUCCESS` 줄의 `elapsedMs` (attempts=1) | 23,000~70,000 | **90,000 초과**가 반복되면 둔화 재발로 보고 원인 조사 |
| `code=TIMEOUT`의 재등장 | 0건 | 1건이라도 나오면 조사 |
| `ollama ps`의 PROCESSOR 열 | `100% GPU` | `%CPU`가 섞이면 VRAM 초과 — 컨텍스트/양자화 확인 |
| `ollama ps`의 CONTEXT 열 | `8192` | 다른 값이면 6-2 드리프트 재발 |
| `attempts` 값 | 1 | 2~3이 흔해지면 검증기 거부가 늘어난 것 — 모델/프롬프트 확인 |

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
워밍 구간(41~44초)만 남는다. 다만 4.0 GB VRAM을 계속 점유한다. 6-1의 180초
예산이면 콜드여도 통과하므로 필수는 아니지만, 최악값을 67.4초에서 44.3초로
낮추면 1.7배 둔화가 재발해도 75초 수준이라 여유가 크게 늘어난다. 둔화가
실제로 재발하면 타임아웃을 더 키우기 전에 이 쪽을 먼저 고려할 만하다.

### 하지 않기로 한 것

- **모델 교체**: 4절대로 `qwen2.5:7b`는 12개 문장 전부를 한 글자도 바꾸지 않고
  되돌려주고, `qwen2.5:3b`는 12개 문장 전부에서 근거 절을 통째로 지운다.
  `qwen3:4b-q8_0`은 8192에서 VRAM을 넘겨 빈 `content`를 내고, 2048에서는
  올바르지만 3회 중 2회가 45초를 넘는다. 45초 예산 안에서 실제로 다듬는
  조합은 하나도 없었다.
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
  있다. 6-1의 180초 예산은 두 경우를 모두 덮지만, **원인을 모르는 채로 예산만
  키운 것**이라는 점을 분명히 해 둔다. 같은 크기의 둔화가 또 일어나면 180초도
  부족해진다(6-1의 운영자 관찰 항목 참고).
- `OLLAMA_CONTEXT_LENGTH=2048` 드리프트의 출처.
  `/home/nextvisit-runner/ByGuardian/infra/llm/`를 sudo 없이 읽을 수 없어
  오래된 체크아웃인지 `.env` 값인지 확인하지 못했다.
- 2048에서 루트 키가 `normalized_sentences`로 바뀌는 메커니즘. 3-3절의
  컨텍스트 창 초과 가설은 관측 14건과 어긋나지 않지만, 창을 넘긴 조건이
  모델 하나·컨텍스트 하나(n=7)뿐이라 반증되지 않았다. `num_ctx`를 단계적으로
  바꿔 경계를 찾는 실험은 운영 컨테이너 재생성이 필요해 하지 않았다.
- `nextvisit-llm-cloudflared-1` 컨테이너가 **언제, 무엇에 의해 제거됐는지**.
  17:10에는 `Restarting (255)`였고 22:56에는 `docker ps -a`에 없었다.
- 17:01:03 API 재생성과 CORS 단일 origin 전환을 **누가/무엇이** 했는지.
  이 세션 밖의 변경이라는 것만 확인했다.
- Ollama 네이티브 `/api/chat`의 `think: false`는 시험하지 않았다. 백엔드가
  `/v1` 경로만 쓰므로 범위 밖으로 두었다.
- `qwen2.5:7b-instruct`가 다른 온도/시드에서 표면 변환을 해낼지는 확인하지
  않았다. 위 12개 문장은 모두 `temperature=0.1, seed=0` 고정에서 나온 것이라
  결정적인 게 당연하며, "이 모델은 절대 다듬지 못한다"까지 말할 근거는 아니다.
  다만 운영이 쓰는 파라미터가 바로 그 고정값이므로 운영 조건에서의 판정으로는
  충분하다.

## 9. 타임아웃 이후: 주간 기록 수정에서 다듬기가 거부되는 문제 (2026-09-17 오후)

6-1의 `NEXTVISIT_LLM_READ_TIMEOUT=180s`가 운영에 들어간 뒤 `TIMEOUT`은 사라졌다.
그런데 다듬기가 받아들여지는 비율이 낮았다. 이 절은 그 원인을 찾고 고친
기록이다. **운영 호스트에는 아무것도 적용하지 않았다.** 호스트에서는 Ollama
HTTP 읽기 호출, `docker logs`/`docker inspect`/`ollama ps` 조회만 했고,
컨테이너 생명주기 명령, DB 접근, sudo, 모델 pull은 하지 않았다. 입력은 모두
합성 데모 시드에서 만들었고 실제 보호자 행은 읽지 않았다.

### 9-1. 운영 결과 4건 (설정: ctx 8192, read timeout 180s, `qwen3:4b-q4_K_M`, max_tokens 3000, 100% GPU)

| 시각(UTC) | 계기 | attempts | elapsedMs | code |
| --- | --- | --- | --- | --- |
| 12:47:59 | `POST /demo` | 1 | 24,740 | `SUCCESS` |
| 12:53:39 | `PUT /me/weeks/6` | 3 | 270,723 | `SURFACE_REWRITE` |
| 12:58:53 | `PUT /me/weeks/6` | 3 | 276,266 | `SURFACE_REWRITE` |
| 13:03:58 | `PUT /me/weeks/6` | 3 | 292,104 | `EMPTY_CONTENT` |

실패 3건은 모두 합성 데모 케이스의 6주차를 고친 요청에서 나왔다.
`toilet`은 level 1 또는 2에 consistency 2, 자유 기록은 "운영 LLM 검증(합성 데모):
화장실 갈 때 손을 잡아드렸습니다.", timeTag는 `AFTERNOON`이었다. 시도 1회가
약 90~97초로, 1~2절에서 잰 41~44초의 두 배를 넘는다.

### 9-2. 각 거부 코드가 검사하는 것

- `SURFACE_REWRITE` (`QuestionOutputGuard.isPermittedSurfaceRewrite`):
  NFC 정규화하고 앞뒤 공백을 지운 문장이 둘 중 하나와 **정확히** 같아야 한다.
  하나는 템플릿 그대로이고, 다른 하나는 템플릿의 마지막 `"습니다. "`를
  `"는데 "`로, 또는 마지막 `"입니다. "`를 `"인데 "`로 바꾼 한 가지 문자열이다.
  나머지는 바이트 단위로 비교한다. 그 앞의 JSON 구조, rank, 길이, 물음표,
  금칙어, 마크다운, 지시문, 숫자 토큰 검사를 모두 통과한 뒤에 이 검사를 한다.
- `EMPTY_CONTENT` (`OpenAiCompatibleLlmClient.assistantContent`): 응답
  `choices[0].message.content`가 없거나 공백이면 이 코드다. 검증기 규칙이 아니라
  클라이언트 예외라서 코디네이터가 `retryRule`을 비우고 처음과 같은 프롬프트로
  재시도한다. `message.reasoning`은 읽지 않는다.
- 재시도: 검증기가 거부하면 system 끝에 `직전 응답은 검증 규칙 <이름>을
  위반했습니다…`만 덧붙인다. 무엇이 틀렸는지는 알려주지 않는다.
- 사용자 메시지: `{"questions":[{"rank":N,"templateSentence":"..."}]}`만
  보낸다. **자유 기록 문장과 timeTag는 LLM에 가지 않는다**
  (`QuestionRewritePromptTest.sendsOnlyRankAndTemplateSentence`가 고정).

### 9-3. 재현 입력

엔진(`Pipeline.run`)을 로컬에서 돌려 템플릿 세 문장을 만들었다.
데모 시드(`DemoSeed.stroke()`)를 그대로 쓴 것이 "데모" 모양이다.
"주간" 모양은 6주차를 `PUT`처럼 다시 만든 것이다. 바뀌지 않은 항목은 5주차
값을 CARRIED로 복사하고(`SnapshotAssembler`), `toilet`은 level 1(또는 2)에
consistency 2, timeTag는 `AFTERNOON`이다. 운영 요청의 painSignal은 알 수 없어서
두 경우로 나눴다. 시드와 같은 신호면 "주간·3주", 신호가 없으면 "주간·2주"다.

| 모양 | rank1 (66자) | rank2 (43자) | rank3 (47자) |
| --- | --- | --- | --- |
| 데모 | 화장실 이용은 혼자 하심으로 바뀌셨는데 집 안에서 걷기는 6주째 그대로입니다. 집 안에서 걷기는 왜 안 늘고 있을까요? | 일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요? | 식사는 혼자 하심으로 바뀌셨는데 마비된 손은 안 씀으로 바뀌었습니다. 괜찮은 걸까요? |
| 주간·3주 | **옮겨 앉기는 지켜보면 됨으로** 바뀌셨는데 … (이하 같음) | 데모와 같음 | 데모와 같음 |
| 주간·2주 | 주간·3주와 같음 | … 4주 중 **2주** 봤습니다. … | 데모와 같음 |

level 1과 2는 같은 템플릿을 만들었다. toilet level이 3에서 내려가면서
`RISE_VS_STALL`의 첫 항목만 `화장실 이용`에서 `옮겨 앉기`로 바뀌었기 때문이다.
**두 모양의 차이는 그것뿐이다.** 문장 수(3), 문장 길이(66/43/47자),
근거 절 수(각 1개), prompt 토큰(446 대 447)이 모두 같다. 자유 기록은 앞에서
본 대로 전달되지 않는다. `AFTERNOON`은 `TIME_OF_DAY` 감지를 만들지만 우선순위가
맨 끝이라 세 문장에 들지 않는다.

요청 본문은 `QuestionRewritePrompt`/`OpenAiCompatibleLlmClient`와 같은 모양
(system 553자, 필드 순서 동일)으로 만들어 호스트의 `127.0.0.1:11434`에
보냈다. 코디네이터와 같은 3회 재시도 루프(거부 규칙 덧붙임 포함)로 돌렸다.
판정은 captured content를 **실제 `QuestionOutputGuard`에 넣어** 확인했다.

### 9-4. 수정 전 결과 (현행 요청)

| 모양 | 회차 | 시도 | 초 | finish | completion 토큰 | reasoning 자 | content 자 | 실제 검증기 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 데모 | 1 | 1 | 25.08 | stop | 612 | 1604 | 240 | 통과 |
| 데모 | 2 | 1 | 28.03 | stop | 813 | 1926 | 240 | 통과 |
| 데모 | 3 | 1 | 26.66 | stop | 813 | 1926 | 240 | 통과 |
| 주간·3주 | 1 | 1 / 2 / 3 | 45.30 / 73.43 / 60.44 | stop ×3 | 1281 / 2024 / 1681 | 3473 / 6333 / 4736 | 244 / 244 / 242 | `SURFACE_REWRITE` / `SURFACE_REWRITE` / 통과 |
| 주간·3주 | 2 | 1 / 2 / 3 | 43.69 / 68.82 / 57.09 | stop ×3 | 1281 / 2024 / 1681 | 3473 / 6333 / 4736 | 244 / 244 / 242 | `SURFACE_REWRITE` / `SURFACE_REWRITE` / 통과 |
| 주간·3주 | 3 | 1 / 2 / 3 | 56.45 / 69.36 / 57.22 | stop ×3 | 1596 / 2024 / 1681 | 4488 / 6333 / 4736 | 244 / 244 / 242 | `SURFACE_REWRITE` / `SURFACE_REWRITE` / 통과 |
| 주간·2주 | 1 | 1 / 2 / 3 | 88.91 / 104.06 / 104.22 | stop / **length** / **length** | 2428 / **3000** / **3000** | 6687 / 7648 / 11154 | 243 / **0** / **0** | `SURFACE_REWRITE` / `EMPTY_CONTENT` / `EMPTY_CONTENT` (합계 297초) |
| 주간·2주 | 2 | 1 | 50.78 | stop | 1402 | 4173 | 244 | 통과 |

첫 시도 수락은 **데모 3/3, 주간 1/5**다. 주간·2주 1회차는 3회 모두 실패했고
합계 297초에 `EMPTY_CONTENT`로 끝났다. 운영 13:03:58의 292초 `EMPTY_CONTENT`와
같은 모양이고, 시도 1회가 89~104초인 것도 운영의 90~97초와 맞는다.
운영 12:53/12:58의 "3회 모두 `SURFACE_REWRITE`" 순서는 그대로 재현하지 못했다.
다만 89초짜리 `SURFACE_REWRITE` 시도는 재현했다.

**`SURFACE_REWRITE`에서 바뀐 것.** 거부된 출력은 모두 같은 실수를 했다.

```
템플릿: … 6주째 그대로입니다. 집 안에서 걷기는 왜 안 늘고 있을까요?   (66자)
출력:   … 6주째 그대로인데. 집 안에서 걷기는 왜 안 늘고 있을까요?     (65자)
허용형: … 6주째 그대로인데 집 안에서 걷기는 왜 안 늘고 있을까요?      (64자)

템플릿: … 4주 중 3주 봤습니다. 통증일 수 있을까요?
출력:   … 4주 중 3주 봤는데. 통증일 수 있을까요?
```

`입니다`→`인데`, `습니다`→`는데`는 바꿨지만 **마침표를 남겼다.**
사실·숫자·항목은 바뀌지 않았다. 검증기는 열거된 결합형이 아니므로 올바르게
거부했다. 사고 기록(reasoning)에는 규칙을 스스로 `replace "입니다." with "인데"`로
옮겨 적은 뒤 `"...그대로인데. 집 안에서..."`를 만드는 과정이 그대로 남아 있다.
재시도 문구는 규칙 이름만 알려 주므로 2회차도 같은 출력을 냈다.

**`EMPTY_CONTENT`의 정체.** `finish_reason=length`, `completion_tokens=3000`,
content 0자다. reasoning(7648자, 11154자)의 뒷부분은 같은 문단의 반복이다.
"`4주 중 2주 봤습니다.`이므로 `봤는데 통증일…`이 맞다"를 되풀이하거나,
"`바뀌었습니다`는 `습니다.`가 아니므로 바꾸지 않는다"를 되풀이한다. **출력
예산을 사고가 전부 썼다.** 반복 루프이므로 `max_tokens`를 늘리면 더 오래 돌 뿐이다.

**왜 주간 모양이 ~90초인가.** 두 모양의 입력 크기는 같다. 달라지는 것은
사고 길이다. 데모는 612~813토큰(25~28초)에서 규칙을 올바르게 옮겨 적고
끝낸다. 주간은 첫 문장 앞부분 몇 글자만 달라도 사고가 "마침표를 남기는
해석"이나 "결합 여부 판단 반복" 쪽으로 가서 1281~3000토큰을 쓴다.
생성 속도는 약 27~29 토큰/초로 일정했다(3000토큰 ≈ 104초). 90~97초는 약
2500~2700토큰에 해당한다. 입력이 아니라 **사고 경로가 시간을 결정한다.**
같은 이유로 8절의 "원인 불명의 1.7배 둔화"도 설명된다. 오늘 같은 호스트에서
데모 모양은 09-14~15 로그와 같은 25~28초였다. 반면 2절의 손으로 만든 입력은
사고를 1259토큰 썼다. 호스트 상태가 아니라 입력에 따른 사고 길이 차이였다.

### 9-5. 가설 검증 (운영 밖)

| # | 가설 | 최소 변경 | 결과 | 판정 |
| --- | --- | --- | --- | --- |
| 1 | 규칙 문구가 모호하다(마침표 포함 여부) | system에 "마침표와 뒤 공백까지 함께 바꾼다, `봤는데. 통증일`은 안 된다" 한 줄 추가 | 주간·2주 5/5 통과. 그러나 주간·3주는 1/3이었다(새 오류 `바뀌는데`, `바뀌었습니다. 바뀌었는데`). 데모는 **0/2**로, 두 번 모두 "마침표를 없앤다"를 반복하다 `length`(3000토큰, 104초) | **기각** (마침표 잔존은 0/10으로 사라졌지만 사고 루프가 더 잦아짐) |
| 2 | 사고 자체가 원인이다 | `reasoning_effort:"none"`(이 버전에서 실제로 사고가 꺼짐. `"low"`는 무효, 47.5초) + "마지막 `습니다. `/`입니다. `가 있으면 반드시 한 번 적용" 한 줄 | 15/15 "통과", 약 5초. 그러나 45개 문장이 **전부 템플릿 그대로**(`IDENTICAL`) | **기각** (수락률이 오른 것처럼 보이지만 `source=LLM`만 붙고 다듬기는 0. 4-1절 판단과 같은 이유로 채택 불가) |
| 3 | 사고를 끈 모델은 규칙 설명이 아니라 **예시**를 따른다 | 가설 2 + 합성 예시 한 쌍(user 템플릿 3개 → assistant 결합형 3개)을 system과 실제 user 사이에 넣음 | 데모·주간·2주·주간·3주 15/15 통과. 45개 문장이 **모두 허용된 결합형**이고 약 5초, 137토큰, reasoning 0자. 다른 템플릿 형식(합성 mixA/B/C, 8개 문장 형식) 9/9 통과 | **채택** |

### 9-6. 수정 (커밋 `d38ccc8`)

- `OpenAiCompatibleLlmClient`: 요청에 `"reasoning_effort":"none"`을 넣는다.
  메시지는 system → (예시 user → 예시 assistant) → 실제 user 순서로 보낸다.
- `QuestionRewritePrompt`: `Prompt`에 `examples`를 추가했다(기존 2인자 생성자는
  유지). 예시는 엔진 템플릿 형식을 따른 합성 문장 3개와 그 결합형이다. 실제
  입력과 겹치지 않는다. system에는 "문장에 마지막 `"습니다. "` 또는 `"입니다. "`가
  있으면 위 변환을 반드시 한 번 적용하세요." 한 줄을 추가했다.
  `/no_think`는 무효지만 해가 없어 그대로 두었다.
- **검증기는 바꾸지 않았다.** `QuestionOutputGuardTest.rejectsJoinThatKeepsTheSentenceBoundaryPeriod`는
  이번에 잡은 "마침표 남긴 결합" 출력을 계속 `SURFACE_REWRITE`로 거부하도록
  고정한다. 예시가 새 사실을 흘리더라도 열거된 결합형 외에는 통과하지 못한다.
- 테스트(먼저 실패 확인 후 구현):
  `QuestionRewritePromptTest.demonstratesTheEnumeratedJoinWithTheSentenceBoundaryRemoved`는
  예시 출력이 **실제 검증기**를 통과하는지 확인한다. 동시에 템플릿과 다르고
  `". "`이 없는지도 확인한다. `retryKeepsTheSameDemonstration`과
  `systemPromptRequiresTheJoinWhenTheBoundaryExists`도 추가했다.
  `OpenAiCompatibleLlmClientTest.disablesReasoningAndSendsTheDemonstrationBeforeTheRealInput`는
  `reasoning_effort`와 메시지 순서를 확인한다. 기존 고정 테스트
  (`/no_think`, 기본값, 2-메시지 계약)는 하나도 고치지 않았다.
- `max_tokens`, 타임아웃, 모델, 컨텍스트 기본값은 그대로다.

### 9-7. 수정 후 결과 (실제 코드 클래스로 보낸 요청)

수정된 `QuestionRewritePrompt` + `OpenAiCompatibleLlmClient` + `QuestionOutputGuard`를
그대로 써서 SSH 포트 포워딩으로 호스트 Ollama에 보냈고, 코디네이터와 같은
재시도 루프를 적용했다. 캡처한 요청은 필드 `reasoning_effort:"none"`, 메시지
`system, user, assistant, user`, system 606자였다.

| 모양 | 회차 | 첫 시도 수락 | 초 | completion 토큰 | reasoning | 문장 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 주간·2주 (level 1) | 5 | **5/5** | 4.35~4.83 | 137 | 0 | 15/15 결합 |
| 주간·3주 (level 1) | 5 | **5/5** | 4.40~4.72 | 137 | 0 | 15/15 결합 |
| 주간·2주 (level 2) | 5 | **5/5** | 4.44~4.75 | 137 | 0 | 15/15 결합 |
| 데모 | 5 | **5/5** | 4.44~4.93 | 136 | 0 | 15/15 결합 |
| 합성 mixA (`RISE_VS_DECLINE`, `AID_CHANGE`, `CONSISTENCY_DROP`) | 5 | 5/5 | 4.15~4.62 | 127 | 0 | 15/15 결합 |
| 합성 mixB (`FLUCTUATION`, `RISE_WITH_PAIN`, `TIME_OF_DAY`) | 5 | 5/5 | 3.68~4.03 | 112 | 0 | 10 결합 + `TIME_OF_DAY` 5 그대로 |
| 합성 mixC (2절 입력의 `RISE_VS_STALL`, `DECLINE_NO_SIGNAL`) | 5 | 5/5 | 2.75~3.10 | 84 | 0 | 10/10 결합 |

전후 비교 (첫 시도 기준):

| 모양 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 데모 | 3/3 (25~28초) | 5/5 (4.4~4.9초) |
| 주간 (2주·3주 합산) | 1/5 (44~89초), 최종 성공 4/5, 1회는 297초 후 `EMPTY_CONTENT` | 15/15 (4.4~4.8초) |

예시 결과 문장: `일어설 때 얼굴을 찡그리시는 걸 4주 중 2주 봤는데 통증일 수 있을까요?`,
`식사는 혼자 하심으로 바뀌셨는데 마비된 손은 안 씀으로 바뀌었는데 괜찮은 걸까요?`

테스트: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean test`로
`:api` 152개(기존 147 + 신규 5), `:engine` 123개를 돌렸고 실패·에러·스킵은 모두 0이다.

### 9-8. 확인하지 못한 것

- **운영 적용 결과.** 배포하지 않았다. 적용 후에는 `PUT /me/weeks/*`에서
  `attempts=1 code=SUCCESS`와 수 초대 `elapsedMs`가 나오는지 봐야 한다.
- **회차가 독립 표본이 아니다.** `temperature=0.1, seed=0`에 사고까지 꺼져서
  같은 입력은 매번 같은 출력을 낸다. "5/5"는 재현성이지 통계적 수락률이 아니다.
  다양성은 모양 수(실제 엔진 모양 3개 + 합성 3개, 템플릿 형식 10개 중
  10개 모두)로만 확인했다. 같은 형식이라도 항목·라벨 조합이 다른 문장과
  미래의 새 템플릿 형식은 확인하지 않았다.
- **운영 입력의 정확한 모양.** 실제 행을 읽지 않았으므로 운영 요청의
  painSignal과 직전 주 값은 시드에서 추정했다. 운영 12:53/12:58의 "3회 모두
  `SURFACE_REWRITE`" 순서는 그대로 재현하지 못했다.
- **문장 자연스러움.** 결합형은 검증기가 원래 허용하던 형태다. 하지만
  `RISE_VS_DECLINE`(`필요해지셨는데 … 필요해지셨는데`)이나 `HAND_DISUSE`
  (`바뀌셨는데 … 바뀌었는데`)처럼 `-는데`가 두 번 나오는 문장이 된다.
  "반드시 적용" 문구 때문에 이런 결합이 매번 일어난다. 수정 전 사고 모드에서도
  데모에서 같은 결합이 나왔으므로 새 형태는 아니다. 제품 판단은 필요하다.
- **`TIME_OF_DAY`는 결합하지 않았다**(5/5 그대로). 검증기 통과이고 사실 변화도
  없지만, 이 형식에서는 다듬기가 0이다.
- **Ollama 버전 의존.** `reasoning_effort:"none"`이 사고를 끄는 것은 0.33.3에서
  확인했다. 다른 버전이나 다른 OpenAI 호환 서버에서는 무시되거나 거부될 수 있다.
  무시되면 수정 전 동작(사고 켬 + 예시)으로 돌아가는데, 그 조합은 측정하지 않았다.
- **예산 재조정.** 한 번에 5초 안쪽이므로 180초 읽기 타임아웃과 3000 출력 토큰은
  이제 크게 남는다. 줄이지 않았다. 줄이려면 운영에서 수정 후 수치를 먼저 봐야 한다.
- prompt 토큰이 약 446에서 675로 늘었다(예시 한 쌍). 속도에는 영향이 없었다.
