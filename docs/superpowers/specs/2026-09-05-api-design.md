# API 설계 — `backend/api`

승인일 2026-09-05. README v3가 제품 스펙이고, 이 문서는 그 §4·§5·§8·§9·§10을 Spring Boot API로 옮기는 계약이다.
README와 어긋나면 README가 이긴다. 단 하나의 예외는 §10의 LLM 클라이언트 선택(아래 6절)이며, README를 같이 고쳤다.

엔진(`backend/engine`)은 완료돼 있다. 이 문서의 API는 엔진을 호출만 하고 판정 로직을 갖지 않는다.

---

## 0. 범위

**이번 계획(api-core)에 들어가는 것.** 온보딩, 주간 기록 저장, 층 2 경과·궤적, 준비 카드, 치료사용 요약, 데모, 인증. 질문 문장은 **템플릿**으로 채운다.

**다음 계획(api-llm)으로 넘기는 것.** 비동기 LLM 호출, 가드레일 재생성·폴백, 캐시 갱신. 이번 계획이 만드는 캐시 구조를 그대로 쓰고 문장만 덮어쓴다.

**범위 밖.** 주간 알림(README §11), 금지 어휘 확장(후속 문서 2번, 치료사 확인 필요), 외래 전날 스케줄러(§8이 "저장 시 캐시 갱신"으로 정의하므로 불필요).

---

## 1. 스택

| 항목 | 선택 | 이유 |
| --- | --- | --- |
| Spring Boot | 3.5.x 최신 패치 | 익숙한 세대. 4.0은 마이그레이션 변수 |
| 저장 | Spring Data JPA + PostgreSQL 16 | README §10 |
| 스키마 | Flyway, `{vendor}` 위치로 postgresql·h2 두 벌 | JSONB는 Postgres, JSON은 H2 |
| 스냅샷 | JSONB 한 컬럼, Hibernate `@JdbcTypeCode(SqlTypes.JSON)` | README §10 "주차 스냅샷은 JSONB 컬럼 하나" |
| 테스트 DB | H2 PostgreSQL 모드 | 도커 없이 CI가 돈다 |
| LLM 클라이언트 | `RestClient`로 OpenAI 호환 엔드포인트 직접 호출 | 호출이 한 종류뿐. Spring AI의 의존성·버전 궁합 위험 제거. base-url 한 줄 교체는 그대로 성립 |
| 인증 | 서블릿 필터 + 인자 리졸버. Spring Security 없음 | 토큰 → 케이스 매핑뿐이라 과함 |
| 시간대 | Asia/Seoul 고정 | 주차 계산 |

Gradle 멀티모듈: `engine`(기존) + `api`(신규). `api`는 `engine`에 `implementation(project(":engine"))`.

---

## 2. 데이터

### 2.1 테이블

```sql
cases
  id                  uuid pk
  observation_set     text        -- 'stroke'
  start_date          date        -- 1주차 기준. 온보딩 날짜 (서울)
  diagnosis           text        -- 'STROKE' | 'OTHER' | 'UNKNOWN'
  paretic_side        text        -- 'LEFT' | 'RIGHT' | 'NONE' | 'UNKNOWN'
  verbal_difficulty   text        -- 'NONE' | 'SOMETIMES' | 'OFTEN'
  next_visit_date     date null
  recovery_code_hash  text        -- sha256 hex. 케이스당 하나
  extra_questions     jsonb       -- ["..."] 보호자가 준비 카드에 직접 추가한 질문
  created_at          timestamptz

guardians
  id            uuid pk
  case_id       uuid fk
  relation      text        -- '딸' '아들' '배우자' '며느리' '사위' '기타' 등 자유 텍스트
  token_hash    text unique -- sha256 hex of UUID
  created_at    timestamptz

therapist_links
  id            uuid pk
  case_id       uuid fk
  token_hash    text unique
  revoked       boolean default false
  created_at    timestamptz

snapshots
  id            uuid pk
  case_id       uuid fk
  week          int         -- 1부터
  kind          text        -- 'BASELINE' | 'WEEKLY' | 'FULL_RECHECK'
  no_change     boolean     -- "달라진 것 없음" 탭이었는지
  author_id     uuid fk guardians
  body          jsonb       -- 2.2
  recorded_at   timestamptz
  unique (case_id, week)

question_cache
  id            uuid pk
  case_id       uuid fk
  week          int         -- 생성 당시 최신 주차
  status        text        -- 'READY' (템플릿 채움) | 'LLM_PENDING' | 'LLM_DONE' | 'LLM_FAILED'
  body          jsonb       -- 2.3
  generated_at  timestamptz
  unique (case_id)          -- 케이스당 최신 하나만 유지
```

### 2.2 스냅샷 본문 (README §5 주차 스냅샷 그대로)

```json
{
  "items": {
    "transfer":   { "level": {"value": 1, "source": "CONFIRMED"}, "aid": {"value": 1, "source": "CONFIRMED"}, "consistency": {"value": 2, "source": "CONFIRMED"}, "hand": null, "note": null },
    "ambulation": { "level": {"value": 2, "source": "CARRIED"},   "aid": {"value": 1, "source": "CARRIED"},   "consistency": null, "hand": null, "note": null },
    "...": "8항목 전부. 항목에 적용되지 않는 축은 null. 적용되지만 이번 케이스에서 비활성인 축(마비 쪽 모름이면 hand)도 null"
  },
  "painSignal": { "STANDING": ["GRIMACE", "GUARDING"] },
  "sleep": 1,
  "freeNote": { "text": "오후만 되면 오른쪽 어깨를 자꾸 만지신다", "timeTag": "AFTERNOON" }
}
```

- `painSignal`: 동작 → 그 동작에서 본 신호 종류 목록. 신호가 없는 동작은 키를 넣지 않는다. 비언어 관찰이 비활성인 케이스는 `null`. 활성이면 매주 필수이며, 아무 신호도 없었으면 `{}`(빈 객체)로 "물어봤고 없었다"를 남긴다. 엔진의 `SignalWeek`가 그걸 창의 분모로 쓴다.
- `freeNote`: 없으면 `null`. `timeTag`는 `MORNING | AFTERNOON | EVENING | ANY | null`.
- 값의 `source`는 `CONFIRMED | CARRIED`. 층 1 원칙: 무엇도 버리지 않는다.

### 2.3 질문 캐시 본문

```json
{
  "questions": [
    {
      "rank": 1,
      "type": "RISE_VS_STALL",
      "items": ["toilet", "ambulation"],
      "signal": null,
      "templateSentence": "화장실 이용은 혼자 하심으로 바뀌셨는데 …",
      "sentence": "화장실 이용은 혼자 하심으로 바뀌셨는데 …",
      "source": "TEMPLATE"
    }
  ],
  "engineDetectionCount": 20
}
```

`sentence`는 화면에 나가는 문장. 이번 계획에서는 항상 `templateSentence`와 같고 `source`는 `TEMPLATE`. api-llm 계획이 `sentence`와 `source`(`LLM`)만 덮어쓴다. `templateSentence`는 폴백과 프롬프트 기준 문장이므로 절대 지우지 않는다.

---

## 3. 주차 규칙

- `week(date) = floor(days(start_date, date) / 7) + 1`, 서울 기준 날짜.
- **온보딩이 1주차 기준선**이다. `kind = BASELINE`, 8항목 전부 `CONFIRMED`.
- **주간 기록은 2주차부터** 받는다. 1주차에 `PUT /me/weeks/1`은 409.
- `PUT /me/weeks/{week}`의 `week`는 **오늘의 주차와 같아야** 한다. 다르면 409. 과거 주차 소급 기록은 이번 범위 밖.
- 같은 주에 다시 저장하면 **덮어쓴다**(upsert). 기록이 없는 주는 행이 없고, 그것이 곧 결측이다.
- **4의 배수 주는 전체 재확인 주**(`kind = FULL_RECHECK`). `GET /me`가 `fullRecheck: true`를 주고, 그 주의 저장은 `noChange = false`이고 8항목이 전부 있어야 한다. 아니면 400.
- **"달라진 것 없음"**(`noChange = true`): 서버가 직전 기록 주의 항목 값을 전부 `source = CARRIED`로 복사한다. 직전 기록이 결측이면 그보다 앞의 최신 기록을 쓴다. `painSignal`·`sleep`·`freeNote`는 요청에서 받는다(§9 4·5단계는 변화가 없어도 묻는다).
- 변경 항목(`noChange = false`, `changedItems`에 든 항목): 그 항목의 **적용되는 모든 축**을 받고 전부 `CONFIRMED`. 나머지 항목은 `CARRIED`.
- 축 적용 규칙: 항목의 축은 `ObservationSet.STROKE`의 선언을 따르고, `hand`는 케이스의 `paretic_side`가 `LEFT | RIGHT`일 때만 활성. 비활성 축은 항상 `null`.

---

## 4. 인증

- **보호자 토큰**: UUID v4 문자열. DB에는 SHA-256 hex만 저장. 요청 헤더 `X-Guardian-Token`. `/me/**` 전부 필수. 없거나 틀리면 401.
- **복구 코드**: 케이스당 하나. 알파벳에서 `I O 0 1`을 뺀 32자 집합으로 8자리, `SecureRandom`. 온보딩 응답에서 **한 번만** 평문으로 보여주고 해시만 저장.
- `POST /guardians/recover { recoveryCode, relation }` → 같은 케이스에 **새 보호자 행**을 만들고 새 토큰을 준다. 새 기기든 두 번째 사람이든 이 경로다. `relation`이 작성자 라벨이 된다.
- **치료사 링크**: 케이스당 활성 링크 하나. UUID, 해시 저장. `GET /t/{token}`은 헤더 없이 읽기 전용. 폐기하면 404.
- 컨트롤러는 `@CurrentGuardian Guardian g` 인자로 현재 보호자와 케이스를 받는다. 필터가 토큰을 해시해 조회하고 요청 속성에 넣으며, 인자 리졸버가 꺼낸다.

---

## 5. 엔드포인트

전부 JSON. 오류는 `{ "code": "...", "message": "..." }`. 400 검증 실패(엔진의 `IllegalArgumentException` 포함), 401 토큰, 404 없음, 409 주차 불일치.

### `GET /catalog` (인증 없음)

프론트가 라벨 표를 따로 들고 있지 않도록 관찰 세트를 통째로 준다.

```json
{
  "set": "stroke",
  "items": [ { "code": "transfer", "label": "침대·의자에서 옮겨 앉기", "phrase": "옮겨 앉기", "group": "mobility", "axes": ["LEVEL","AID","CONSISTENCY"] } ],
  "axes": { "LEVEL": [ {"value":0,"label":"대부분 도움"}, "..."], "AID": ["..."], "CONSISTENCY": ["..."], "HAND": ["..."] },
  "signalActions": [ {"code":"STANDING","label":"일어설 때"} ],
  "signalKinds":   [ {"code":"GRIMACE","label":"찡그림"}, {"code":"VOCAL","label":"소리 냄"}, {"code":"GUARDING","label":"팔을 감싸거나 피함"} ],
  "timeTags":      [ {"code":"MORNING","label":"오전"}, "..." ]
}
```

신호 종류의 화면 라벨은 엔진의 문장용 구("얼굴을 찡그리시는 걸")가 아니라 위의 짧은 명사형이다. API가 매핑한다.

### `POST /cases` — 온보딩 (README §9 화면 1)

```json
// 요청
{
  "relation": "딸",
  "diagnosis": "STROKE",
  "pareticSide": "RIGHT",
  "verbalDifficulty": "OFTEN",
  "nextVisitDate": "2026-09-30",
  "baseline": {
    "items": { "transfer": {"level":1,"aid":1,"consistency":2,"hand":null}, "...": "8항목 전부, 적용되는 축 전부" },
    "painSignal": null, "sleep": null, "freeNote": null
  }
}
// 응답 201
{ "caseId": "…", "guardianToken": "…", "recoveryCode": "K7M3P9RW", "week": 1 }
```

- `diagnosis`가 무엇이든 MVP는 `stroke` 세트다(README §5).
- `verbalDifficulty`가 `SOMETIMES | OFTEN`이면 비언어 관찰 활성(README §5).
- 기준선은 `week = 1`, `kind = BASELINE`으로 저장한다.

### `POST /guardians/recover`

`{ "recoveryCode": "…", "relation": "아들" }` → `200 { "guardianToken": "…", "caseId": "…" }`. 틀리면 404.

### `GET /me`

```json
{
  "caseId": "…", "relation": "딸",
  "today": "2026-09-05", "week": 6,
  "fullRecheck": false,
  "signalsEnabled": true, "handEnabled": true,
  "canRecordThisWeek": true,        // week >= 2
  "recordedThisWeek": false,
  "lastRecordedWeek": 5,
  "nextVisitDate": "2026-09-30"
}
```

### `PUT /me/weeks/{week}` — 주간 기록 (README §9 화면 2)

```json
{
  "noChange": false,
  "changedItems": { "toilet": {"level":3,"aid":null,"consistency":2,"hand":null,"note":"이제 혼자 가심"} },
  "painSignal": { "STANDING": ["GRIMACE"] },
  "sleep": 1,
  "freeNote": { "text": "…", "timeTag": "AFTERNOON" }
}
```

응답 `200 { "week": 6, "kind": "WEEKLY", "questionsRefreshed": true }`. 저장 직후 **동기적으로** 질문 캐시를 템플릿으로 갱신한다(6절). 검증 실패는 400에 항목·축·이유를 담는다.

### `GET /me/progress` — 층 2 기본 화면 (README §9 화면 3, §6 층별 처리)

```json
{
  "week": 6,
  "silent": false,
  "changes": [
    { "item": "toilet", "label": "화장실 이용", "axis": "LEVEL", "axisLabel": "도움 수준",
      "status": "SUSTAINED", "duration": 4, "from": "지켜보면 됨", "to": "혼자 하심",
      "message": "화장실 이용은 지켜보면 됨에서 혼자 하심으로 바뀌었습니다. 이 변화가 4주째 유지되고 있습니다." },
    { "item": "stairs", "label": "문턱·계단", "axis": "CONSISTENCY", "axisLabel": "한 주 일관성",
      "status": "OBSERVED_ONCE", "duration": 1, "from": "대체로", "to": "좋은 날만",
      "message": "문턱·계단의 한 주 일관성은 한 번 달라진 것으로 관찰됐습니다. 아직 변화라고 보기 어렵습니다." }
  ],
  "transitions": [
    { "item": "grooming", "label": "세수·양치", "axis": "LEVEL",
      "message": "3주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다." }
  ],
  "questions": [ { "rank": 1, "type": "RISE_VS_STALL", "sentence": "…", "source": "TEMPLATE" } ]
}
```

`questions`는 준비 카드와 같은 문장이되 `evidence`는 뺀다. 경과 화면은 가볍게, 근거는 준비 카드에서.

규칙 (README §6 층별 처리 표 그대로):

- `changes`에는 `OBSERVED_ONCE`와 `SUSTAINED`만 들어간다. `NO_CHANGE`와 `FLUCTUATING`은 **빠진다**. 축 전부(level·aid·consistency·hand)를 각각 따로 본다.
- `transitions`: 이번 주까지의 판정이 `FLUCTUATING`이고 **지난 주까지의 판정이 `SUSTAINED`**였던 항목·축. 엔진이 결정론적이라 지난 주까지의 `CaseInput`으로 한 번 더 돌려서 비교한다. 다음 주엔 지난 주까지의 판정도 `FLUCTUATING`이라 자연히 사라진다.
- `silent = changes.isEmpty() && transitions.isEmpty() && questions.isEmpty()`. 이때 화면은 "이번 기간에는 바뀐 항목이 없습니다".
- 메시지 문구 규칙. 주어는 `level` 축이면 `"{항목 문장형}은"`, 다른 축이면 `"{항목 문장형}의 {축 라벨}은"`. 조사는 엔진 `Josa.eunNeun`과 `Josa.euroRo`만 쓴다(이/가는 엔진에 없고 필요도 없다).
  - `SUSTAINED`: `"{주어} {이전 라벨}에서 {현재 라벨}으로 바뀌었습니다. 이 변화가 {duration}주째 유지되고 있습니다."` 이전 라벨은 `Verdict.trajectory()`에서 `since` 주 직전 관찰의 값이다.
  - `OBSERVED_ONCE`: `"{주어} 한 번 달라진 것으로 관찰됐습니다. 아직 변화라고 보기 어렵습니다."`
  - 예: `"집 안에서 걷기의 보조 도구는 워커에서 지팡이로 바뀌었습니다. 이 변화가 2주째 유지되고 있습니다."`
  - **모든 메시지는 `Templates.containsForbiddenWord`를 통과해야 하며 테스트가 이를 검사한다.** (질문형 검사는 적용하지 않는다. 층 2 문구는 의도적으로 평서문이다.)
- `duration`이 1인 `SUSTAINED`는 나오지 않는다(엔진 규칙상 hold≥2).

### `GET /me/trajectory` — 층 2 궤적 탭 (README §9 화면 3-b)

7절의 치료사용 `items` 블록과 같은 모양. **판정 문구 없음.** 층 2 탭과 Layer 3가 같은 DTO를 쓴다.

### `GET /me/prep-card` — 준비 카드 (README §9 화면 4)

```json
{
  "week": 6, "nextVisitDate": "2026-09-30",
  "questions": [
    { "rank": 1, "type": "RISE_VS_STALL", "sentence": "…", "source": "TEMPLATE",
      "evidence": {
        "items": [ { "code":"toilet", "label":"화장실 이용", "axis":"LEVEL", "values":[ {"week":1,"value":2,"label":"지켜보면 됨","source":"CONFIRMED"}, "…" ] },
                   { "code":"ambulation", "…":"…" } ],
        "signal": null
      } },
    { "rank": 2, "type": "STALL_WITH_PAIN", "sentence": "일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", "source": "TEMPLATE",
      "evidence": { "items": ["…"], "signal": { "action":"STANDING","actionLabel":"일어설 때","kind":"GRIMACE","kindLabel":"찡그림","weeks":[3,5,6],"window":4 } } }
  ],
  "extraQuestions": ["약은 지금처럼 계속 드려도 될까요?"],
  "emptyMessage": null,
  "therapistGlance": [ "화장실 이용: 지켜보면 됨 → 혼자 하심 (3주차부터)", "식사: 지켜보면 됨 → 혼자 하심 (4주차부터), 마비 쪽 손: 거들기만 → 안 씀 (4주차부터)" ]
}
```

- 질문은 캐시에서 읽는다. 최대 3개. 없으면 `questions: []`, `emptyMessage: "이번 기간 관찰에서 달라진 것이 없었습니다"`.
- `evidence`는 감지의 `items`에 해당하는 항목의 궤적(문장이 언급한 축)과, 신호가 있으면 신호의 관찰 주차. 캐시가 아니라 요청 시 엔진 결과에서 만든다. `signal.weeks`는 엔진의 창(최근 기록 4주) 안에서 그 동작·종류가 관찰된 주차이며 스냅샷의 `painSignal`에서 직접 센다. `window`는 창의 크기.
- `therapistGlance`: `SUSTAINED` 판정이 있는 항목마다 한 줄. 한 항목에 여러 축이 있으면 같은 줄에 쉼표로 잇는다. 형식은 `"{항목 라벨}: {이전} → {현재} ({since}주차부터)"`, 축이 level이 아니면 값 앞에 `"{축 라벨}: "`. 최대 4줄, 항목 표 순서. 판정 단어 없이 값만.
- `PUT /me/prep-card/extra { "questions": ["…"] }` → 보호자 추가 질문 전체 교체. 길이 각 200자, 최대 5개.

### `GET /me/therapist-link`

`{ "url": "/t/{token}", "token": "…" }`. 활성 링크가 있으면 그것을, 없으면 만든다. 프론트가 QR·인쇄로 만든다.

### `GET /t/{token}` — 치료사용 관찰 요약 (README §9 화면 5, Layer 3)

```json
{
  "generatedAt": "2026-09-05T09:00:00+09:00",
  "weeks": [1,2,3,4,5,6],
  "items": [
    { "code":"toilet", "label":"화장실 이용", "changed": true,
      "axes": [
        { "axis":"LEVEL", "axisLabel":"도움 수준",
          "values":[ {"week":1,"value":2,"label":"지켜보면 됨","source":"CONFIRMED"}, {"week":2,"value":2,"label":"지켜보면 됨","source":"CARRIED"}, "…" ] }
      ] },
    { "code":"bathing", "label":"목욕", "changed": false, "axes":[ "…" ] }
  ],
  "signals": [ { "action":"STANDING","actionLabel":"일어설 때","kind":"GRIMACE","kindLabel":"찡그림","weeks":[2,3,5,6] } ],
  "signalsEnabled": true,
  "freeNotes": [ { "week":3, "text":"오후만 되면 오른쪽 어깨를 자꾸 만지신다", "timeTag":"AFTERNOON", "timeTagLabel":"오후" } ],
  "questions": [ "화장실 이용은 …", "일어설 때 …", "식사는 …" ],
  "extraQuestions": [ "…" ],
  "density": { "totalWeeks": 6, "recordedWeeks": 6, "confirmedWeeks": 5, "authors": ["딸"] },
  "authorChanges": [ { "week": 4, "from": "딸", "to": "아들" } ],
  "disclaimer": "이 기록은 보호자가 가정에서 관찰해 남긴 것입니다. 측정이나 평가가 아니며 검사 결과를 포함하지 않습니다."
}
```

Layer 3 계약 (README §9 화면 5 "제외" 목록):

- **판정 필드가 없다.** `status`, `direction`, `duration`, 감지 종류, "상승/하락" 단어 어느 것도 응답에 없다. 테스트가 응답 JSON 문자열에 `SUSTAINED`, `FLUCTUATING`, `OBSERVED_ONCE`, `"status"`, `"direction"`이 없음을 검사한다.
- `changed`는 "어느 축이든 값이 한 번이라도 달라졌는가"라는 사실이지 판정이 아니다. 화면이 변화 없는 항목을 한 줄로 접는 데 쓴다.
- `confirmedWeeks`: 항목 값 중 하나라도 `CONFIRMED`인 주의 수. 기준선과 전체 재확인은 항상 포함, "없음" 탭 주는 제외.
- `authorChanges`: 직전 기록 주와 작성자가 다른 주.
- 자유 기록은 **원문 그대로**. 요약·정제 없음.

### `POST /demo` (인증 없음)

시드로 케이스를 만든다. `start_date = 오늘 − 35일`(오늘이 6주차), 보호자 `딸`, `STROKE`, `RIGHT`, `OFTEN`, 외래 `오늘 + 3일`. `DemoSeed.stroke()`의 항목·축 시계열과 신호를 6개 주간 스냅샷으로 전치하고(2주차는 `noChange = true`, 값은 `CARRIED`), README §10의 자유 기록 세 줄을 3·4·6주차에 `AFTERNOON`으로 넣는다. 질문 캐시를 템플릿으로 채운다. 응답 `201 { "caseId", "guardianToken", "recoveryCode", "therapistUrl" }`.

같은 시드로 이미 만든 케이스가 있어도 매번 새로 만든다. 데모는 지워도 되는 데이터다.

### `GET /health` — `{ "status": "ok" }`.

---

## 6. 엔진 연결

### 6.1 변환기는 엔진 안에

엔진에 추가한다 (`nextvisit.engine`):

```java
public record WeekRecord(
    int week,
    Map<String, Map<Axis, Observation>> values,   // 항목 코드 → 축 → 그 주의 관찰 (week·source 포함)
    Set<SignalKey> signals,                       // null = 그 주에 신호를 묻지 않음(비활성). 빈 집합 = 물었고 없었음
    TimeTag noteTag                               // null = 태그 없음. 기록된 주마다 WeeklyNote가 하나 생긴다
) {}

public static CaseInput CaseInput.fromWeeks(ObservationSet set, List<WeekRecord> weeks)
```

- 항목별·축별 시계열로 전치한다. 주차 오름차순 정렬, 중복 주차는 예외.
- `signals != null`인 주만 `SignalWeek`를 만든다. 비언어 관찰이 비활성인 케이스는 `SignalWeek`가 하나도 없고, 따라서 패턴도 없다.
- 기록된 주마다 `WeeklyNote(week, noteTag)`를 만든다(README §5 자유 기록 규약).
- 축이 어떤 주에는 있고 어떤 주에는 없으면(예: 마비 쪽을 나중에 알게 됨), 없는 주는 그 축의 결측이다.
- 기존 `CaseInput` 생성자의 검증(축 부분집합, 값 범위)이 그대로 걸린다.

API의 `EngineBridge`는 스냅샷 행 → `WeekRecord` 매핑만 한다. 판정은 전부 `Pipeline.run`.

### 6.2 질문 캐시 갱신 (이번 계획: 템플릿)

`QuestionService.refresh(caseId)`:

1. 스냅샷 전부 → `CaseInput.fromWeeks` → `Pipeline.run`.
2. `selected`와 `sentences`를 2.3의 모양으로 `question_cache`에 upsert. `sentence = templateSentence`, `source = TEMPLATE`, `status = READY`.
3. `PUT /me/weeks/{week}` 저장 직후와 `POST /demo`에서 호출. 동기.

api-llm 계획은 이 뒤에 비동기 단계를 붙여 `sentence`·`source`·`status`만 갱신한다.

### 6.3 층 2 전환 문구

`ProgressService`가 `Pipeline.run(전체)`와 `Pipeline.run(마지막 주 제외)` 두 결과의 항목·축별 `status`를 비교한다. 스냅샷이 하나뿐이면 전환 없음.

---

## 7. 공통 DTO — 궤적 블록

층 2 궤적 탭과 Layer 3가 공유한다. 값·라벨·출처만 있고 판정은 없다.

```json
{ "code":"toilet", "label":"화장실 이용", "changed": true,
  "axes": [ { "axis":"LEVEL", "axisLabel":"도움 수준", "values":[ {"week":1,"value":2,"label":"지켜보면 됨","source":"CONFIRMED"} ] } ] }
```

축 라벨: `LEVEL 도움 수준`, `AID 보조 도구`, `CONSISTENCY 한 주 일관성`, `HAND 마비 쪽 손`. 값 라벨은 엔진 `Labels.of`.

---

## 8. 오류·검증

- 요청 검증은 Bean Validation + 서비스 검증. 항목 코드가 세트에 없음, 축이 항목에 적용 안 됨, 값 범위 밖, 전체 재확인 주에 항목 누락, 비언어 활성인데 `painSignal` 누락 → 400 `{ code: "VALIDATION", message: "toilet: HAND는 이 항목에 적용되지 않습니다" }`.
- 엔진의 `IllegalArgumentException`은 400으로 매핑하되 메시지를 그대로 노출한다(엔진이 항목·주차를 넣어준다).
- 주차 불일치 → 409 `WEEK_MISMATCH`. 1주차 주간 기록 시도 → 409 `BASELINE_ONLY`.

---

## 9. 테스트 전략

- **엔진 확장** (`CaseInput.fromWeeks`): 엔진 단위 테스트. 시드를 주차별로 전치했다가 되돌리면 `DemoSeed.stroke()`와 같은 `PipelineResult`가 나와야 한다.
- **서비스**: 저장소는 H2, LLM 없음. 주차 계산(경계: 7일째, 8일째), 복사 규칙, 전체 재확인 검증, 전환 문구 발생·소멸, 층 2 필터링(흔들림 숨김), 준비 카드 빈 상태.
- **웹**: MockMvc. 401/400/409 경로, 응답 모양.
- **Layer 3 계약**: 치료사 응답 JSON 문자열에 판정 단어가 없음.
- **층 2 문구 안전성**: 모든 `changes[].message`와 `transitions[].message`가 `Templates.containsForbiddenWord == false`.
- **끝까지**: `POST /demo` → `GET /me/prep-card`에서 README §10의 세 문장이 `source: TEMPLATE`로 나온다. `GET /t/{token}`에 6주 궤적과 자유 기록 세 줄이 원문 그대로.
- Postgres 실제 실행은 Compose로 수동 확인. CI는 H2.

---

## 10. 파일 구조

```
backend/
  settings.gradle.kts              include("engine", "api")
  engine/…/CaseInput.java          fromWeeks 추가
  engine/…/WeekRecord.java         신규
  api/
    build.gradle.kts
    src/main/java/nextvisit/api/
      ApiApplication.java
      common/    ApiError, GlobalExceptionHandler, Hashing, WeekCalculator, KoreaClock
      auth/      Guardian, GuardianRepository, GuardianAuthFilter, CurrentGuardian, CurrentGuardianResolver, WebConfig
      catalog/   CatalogController, CatalogDto, KindLabels
      cases/     CaseEntity, CaseRepository, CaseService, CaseController, OnboardingRequest, MeResponse, RecoverRequest
      snapshots/ Snapshot, SnapshotBody, SnapshotRepository, SnapshotService, WeeklyRecordRequest, SnapshotController
      engine/    EngineBridge
      progress/  ProgressService, ProgressController, Layer2Copy, TrajectoryDto, TrajectoryMapper
      questions/ QuestionCache, QuestionCacheBody, QuestionCacheRepository, QuestionService, PrepCardController, PrepCardDto
      therapist/ TherapistLink, TherapistLinkRepository, TherapistLinkController, TherapistSummaryService, TherapistController, TherapistSummaryDto
      demo/      DemoService, DemoController
      health/    HealthController
    src/main/resources/
      application.yml
      db/migration/postgresql/V1__init.sql
      db/migration/h2/V1__init.sql
    src/test/resources/application-test.yml
    src/test/java/nextvisit/api/…
```

---

## 11. 결정 기록

- **RestClient over Spring AI** — 승인됨 2026-09-05. README §10 갱신.
- **Boot 3.5** — 4.0 회피.
- **H2로 테스트** — 도커 비의존.
- **주차는 서버 계산, 같은 주 덮어쓰기, 과거 소급 없음** — 단순성. 틀리면 소급 엔드포인트 추가.
- **복구 코드는 케이스당 하나, 재등록은 새 보호자 행** — 작성자 구분을 공짜로 얻는다.
- **Spring Security 없음** — 필터 하나로 충분.
- **스케줄러 없음** — §8 정의상 저장 시 갱신으로 충분.
- **질문 캐시는 케이스당 최신 하나** — 주차별 이력은 스냅샷에서 언제든 재계산 가능(결정론).
