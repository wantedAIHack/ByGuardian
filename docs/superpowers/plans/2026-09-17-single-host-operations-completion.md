# 단일 호스트 운영 완료 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실제 운영 구성(Ubuntu 노트북 단일 호스트)을 정식 설계로 문서화하고, 그 기준의 완료 조건인 도메인 연결·백업 복원·LLM 활성화를 증거와 함께 끝낸다.

**Architecture:** API(Spring Boot), PostgreSQL, Ollama, cloudflared가 모두 한 대의 Ubuntu/NVIDIA 노트북에서 Docker로 돌고, 외부에는 Cloudflare Tunnel만 노출된다. 프런트엔드는 Cloudflare Pages가 `main`을 빌드해 배포한다. API와 Ollama가 같은 호스트에 있으므로 LLM 호출은 Docker 내부 네트워크로 끝나며, `llm.<도메인>` 공개 호스트명과 Access 서비스 토큰은 이 구성에서 불필요하다.

**Tech Stack:** Ubuntu 24.04, Docker Compose, Spring Boot(Java 21), PostgreSQL, Ollama(GTX 1060), cloudflared, Cloudflare Pages, React 19 PWA

**Spec:** `docs/superpowers/specs/2026-09-17-single-host-deployment-design.md` (Task 2에서 작성하며, 그 전까지는 대체된 `docs/superpowers/specs/2026-09-10-full-service-deployment-design.md`를 참조 이력으로만 읽는다)

## 2026-09-17 실사로 확정된 사실

계획을 쓴 뒤 운영 호스트를 직접 확인해 아래를 확정했다. Task 1은 이 값을 재확인하는 일만 남았다.

| 항목 | 확인된 값 |
| --- | --- |
| 실행 중인 스택 | `nextvisit-demo` (`/home/nextvisit-runner/demo/compose.demo.yml`), `nextvisit-llm` (`/home/nextvisit-runner/ByGuardian/infra/llm/compose.yml` + `compose.gpu.yml`) |
| 컨테이너 | `nextvisit-demo-api-1`(`nextvisit-api:demo`), `nextvisit-demo-postgres-1`(`postgres:16-alpine`), `nextvisit-llm-ollama-1`(`nextvisit-ollama:0.33.3`), 모두 `restart: unless-stopped` |
| 공개 경계 | API·Ollama·PostgreSQL 모두 loopback 전용. 호스트의 공개 포트는 SSH뿐. cloudflared는 host systemd 서비스 |
| LLM 설정 | `NEXTVISIT_LLM_ENABLED`는 **이미 `true`**, base-url `http://ollama:11434/v1`, 모델 `qwen3:4b-q4_K_M` |
| LLM 실제 결과 | 2026-09-16T15:22Z·15:24Z 기준 `code=TIMEOUT attempts=3 elapsedMs=135025`. **한 번도 성공하지 못하고 템플릿으로 폴백 중** |
| 모델 보유 | `qwen3:4b-q4_K_M`, `qwen3:4b-q8_0`, `qwen2.5:7b-instruct-q4_K_M`, `qwen2.5:3b-instruct-q8_0` |
| 직접 호출 | 짧은 프롬프트 5.9초, `ollama ps`가 `100% GPU`, context 8192. 단 qwen3는 추론 모델이라 응답이 `reasoning`으로 차고 `content`가 비어 나왔다 |
| 운영 API 버전 | 이미지 생성 시각 2026-09-14T22:46Z. 이후 백엔드 커밋 `a3ff703`(지난 날짜 진료일 저장 금지)이 **운영에 빠져 있다** |
| 권한 | `milo`는 docker 그룹. `/home/nextvisit-runner`와 `/etc/nextvisit`, `/etc/cloudflared`는 읽을 수 없고 sudo는 비밀번호가 필요하다 |

## Global Constraints

- 운영 호스트는 SSH 별칭 `llm`(LAN `172.30.1.46`), 계정 `milo`. 외부 공개 포트는 SSH뿐이며 API·DB·Ollama는 loopback 전용을 유지한다.
- 운영 도메인은 `byguardian.site`. FE는 `app.byguardian.site`, API는 `api.byguardian.site`. `llm.byguardian.site`는 만들지 않는다.
- 프런트엔드 빌드는 Node `22.22.2`. 백엔드는 Java 21.
- `docker compose down --volumes`를 운영에서 사용하지 않는다.
- Tunnel token, DB 비밀번호, 보호자 토큰, 치료사 토큰, 보호자 원문, 모델 응답을 커밋·로그·보고서 어디에도 남기지 않는다. 증거로는 SHA, 상태 코드, 시각, 소요 시간, 고정 결과 코드만 남긴다.
- 운영 DB에 이미 들어간 합성 데모 케이스(2026-09-17 검증분, 이어받기 코드 `X4QLN2FK` 포함)는 삭제 기능이 없으므로 그대로 두고, 실제 보호자 데이터와 구분해 기록한다.
- 색으로 호전·악화·위험도를 표현하지 않는다는 제품 규칙과 기존 API 계약은 이 계획에서 바꾸지 않는다.

---

### Task 1: 운영 호스트 실사와 증거 기록

**Files:**
- Create: `docs/qa/2026-09-17-single-host-inventory.md`

**Interfaces:**
- Consumes: 없음(선행 조건: 사용자가 `sudo usermod -aG docker milo` 실행 완료)
- Produces: 실제 compose 프로젝트 경로, API 컨테이너 이미지와 환경변수 이름 목록, Ollama 모델 태그, cloudflared 라우트, 백업 존재 여부. Task 2~6이 이 문서의 값을 인용한다.

- [ ] **Step 1: docker 접근 확인**

Run: `ssh llm 'docker ps --format "{{.Names}}\t{{.Image}}\t{{.Status}}"'`
Expected: 권한 오류 없이 컨테이너 목록 출력. `permission denied`가 나오면 사용자가 재로그인하도록 요청하고 중단한다.

- [ ] **Step 2: 스택 구성 수집**

```bash
ssh llm 'docker compose ls; \
  docker inspect --format "{{.Name}} {{.Config.Image}} {{.HostConfig.RestartPolicy.Name}}" $(docker ps -q); \
  docker inspect --format "{{range .Mounts}}{{.Type}} {{.Source}} -> {{.Destination}}{{println}}{{end}}" $(docker ps -q)'
```
Expected: compose 프로젝트의 working_dir(예: `/opt/nextvisit/...`), 이미지 태그, 재시작 정책, 데이터 볼륨 경로.

- [ ] **Step 3: API 환경변수의 이름만 수집**

```bash
ssh llm 'docker inspect --format "{{range .Config.Env}}{{println .}}{{end}}" $(docker ps -qf name=api | head -1) | cut -d= -f1 | sort'
```
Expected: `NEXTVISIT_LLM_ENABLED`, `NEXTVISIT_CORS_ORIGINS`, `NEXTVISIT_DB_*` 등 이름 목록. **값은 출력하지 않는다.** 값 확인이 필요한 항목은 이름과 설정 파일 경로만 적는다.

- [ ] **Step 4: LLM·Tunnel·백업 상태 수집**

```bash
ssh llm 'docker exec $(docker ps -qf name=ollama | head -1) ollama list; \
  docker exec $(docker ps -qf name=ollama | head -1) ollama ps; \
  sudo ls -la /etc/cloudflared 2>/dev/null | head; \
  sudo crontab -l 2>/dev/null | grep -i -E "backup|dump" ; \
  sudo ls -la /var/lib/nextvisit/backups /opt/nextvisit/backups 2>/dev/null'
```
Expected: 모델 태그와 GPU 비율, cloudflared 설정 파일 존재, 백업 크론·디렉터리의 존재 여부(없으면 "없음"으로 기록).

- [ ] **Step 5: 실사 문서 작성**

`docs/qa/2026-09-17-single-host-inventory.md`에 표로 적는다. 항목: 컨테이너별 이미지·재시작 정책·볼륨, API 환경변수 이름 목록, Ollama 모델 태그, Tunnel 설정 위치, 백업 유무, loopback 전용 포트 확인 결과(`ss -tlnp`), GPU 모델·드라이버. 각 줄 끝에 확인 명령을 적는다. 비밀값은 쓰지 않는다.

- [ ] **Step 6: 커밋**

```bash
git add docs/qa/2026-09-17-single-host-inventory.md
git commit -m "docs: record the real single-host production inventory"
```

---

### Task 2: 단일 호스트 배포 설계서 작성

**Files:**
- Create: `docs/superpowers/specs/2026-09-17-single-host-deployment-design.md`
- Modify: `docs/superpowers/specs/2026-09-10-full-service-deployment-design.md` (문서 첫머리에 대체 표시만 추가)

**Interfaces:**
- Consumes: Task 1의 실사 문서
- Produces: 새 완료 조건 번호 1~8. Task 3~7이 이 번호를 인용한다.

- [ ] **Step 1: 새 설계서 작성**

다음 절을 포함한다.

1. 결정 요약 — 단일 호스트, Tunnel만 공개, EC2/ECR/SSM/S3/Caddy 미사용, `llm` 공개 호스트명 미사용, LLM은 Docker 내부 호출.
2. 현재 기준선 — Task 1 실사 표를 인용.
3. 아키텍처 — Pages(FE) → Tunnel → API(127.0.0.1:8080) → PostgreSQL, API → Ollama(127.0.0.1:11434).
4. 완료 조건 — 아래 8개를 그대로 적는다.
   1. `main` 한 곳에 FE·BE·infra와 실제와 일치하는 문서가 있다.
   2. PR에서 FE test/typecheck/build, Java test, infra 정적 검사, 통합 E2E가 hosted CI에서 통과한다.
   3. `app.byguardian.site`가 Pages production 빌드를 제공하고 브라우저는 `api.byguardian.site`만 호출하며, 모든 client route가 직접 진입·새로고침 뒤에도 복구된다.
   4. 호스트는 SSH 외 공개 포트가 없고 API·DB·Ollama는 loopback 전용이며, 외부 `/health`가 DB 상태와 함께 정상이다.
   5. DB 볼륨과 모델 볼륨이 재배포 뒤 유지되고, 백업의 실제 복원 시험을 한 번 통과한다.
   6. 노트북 재부팅 뒤 Docker, PostgreSQL, API, Ollama, cloudflared가 수동 조치 없이 복구된다.
   7. LLM을 끄거나 Ollama를 내려도 기록 저장과 템플릿 질문이 성공한다.
   8. 저장소·CI 로그·보고서에 비밀값과 보호자 원문이 없다.
5. 이번 범위 밖 — AWS 이전, 다중 호스트, runner 자동 배포, `generationStatus` 필드 추가.
6. 대체 이력 — 2026-09-10 설계서의 어떤 결정이 왜 폐기됐는지(EC2 미구축, 동일 호스트 배치로 Access 불필요) 한 문단.

- [ ] **Step 2: 옛 설계서에 대체 표시 추가**

`docs/superpowers/specs/2026-09-10-full-service-deployment-design.md` 첫 제목 바로 아래에 다음을 넣는다. 본문은 지우지 않는다.

```markdown
> **이 설계는 2026-09-17자 [단일 호스트 배포 설계](2026-09-17-single-host-deployment-design.md)로 대체됐다.**
> EC2·ECR·SSM·S3·Caddy 구성은 구축되지 않았고, 운영은 Ubuntu 노트북 한 대에서 이뤄진다.
> 이 문서는 당시 결정의 이력으로만 읽는다.
```

- [ ] **Step 3: 링크 검사**

Run: `grep -rn "2026-09-10-full-service-deployment-design" --include=*.md . | grep -v node_modules`
Expected: 참조하는 모든 문서를 확인하고, 현재 구성을 설명하는 문장이면 새 설계서로 바꾼다.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/specs
git commit -m "docs: replace the EC2 deployment design with the single-host design"
```

---

### Task 3: README와 소유자 체크리스트를 실제에 맞춤

**Files:**
- Modify: `README.md:15-25` (구현·배포 상태 절)
- Modify: `infra/llm/OWNER_CHECKLIST.md:8-60` (현재 상태 절)
- Modify: `backend/README.md` (배포 관련 서술이 EC2를 가정하는 부분)

**Interfaces:**
- Consumes: Task 2의 완료 조건 번호
- Produces: 루트 README의 "운영 상태" 표. Task 7의 최종 보고서가 이 표를 인용한다.

- [ ] **Step 1: README 상태 절 교체**

현재 문장 "현재 이 구현은 **외부에 적용·배포되지 않았습니다**"를 지우고 다음 표를 넣는다. 값은 확인한 것만 적는다.

```markdown
## 운영 상태 (2026-09-17 확인)

| 항목 | 값 |
| --- | --- |
| 보호자 화면 | https://app.byguardian.site (Cloudflare Pages, `main` 자동 배포) |
| API | https://api.byguardian.site (Ubuntu 노트북, Cloudflare Tunnel) |
| 배포된 FE 커밋 | `build.json`의 `commit` 값 |
| LLM | 활성/비활성 상태를 그대로 적는다 |
| 검증 근거 | docs/qa/2026-09-17-single-host-inventory.md |
```

- [ ] **Step 2: 소유자 체크리스트 갱신**

`infra/llm/OWNER_CHECKLIST.md`의 "현재 상태" 절에 2026-09-17 항목을 추가한다. Cloudflare Access·Tunnel 공개 호스트명·runner group 항목은 삭제하지 말고 **"단일 호스트 구성에서 불필요해져 폐기"**로 표시한다. 폐기 이유 한 줄을 함께 적는다.

- [ ] **Step 3: 문서 주장 검사**

Run: `grep -rn "EC2\|ECR\|SSM\|Caddy" README.md backend/README.md infra/llm/README.md infra/llm/OWNER_CHECKLIST.md`
Expected: 남은 문장이 모두 이력 서술이거나 실제와 일치한다. 현재 구성을 잘못 설명하는 문장이 없다.

- [ ] **Step 4: 커밋**

```bash
git add README.md backend/README.md infra/llm/OWNER_CHECKLIST.md
git commit -m "docs: state the live single-host deployment"
```

---

### Task 4: `app.byguardian.site` 연결과 CORS 전환

**Files:**
- Modify: 운영 호스트의 API 환경변수 `NEXTVISIT_CORS_ORIGINS` (Task 1에서 찾은 compose/env 파일)
- Modify: `frontend/e2e/warm-observation.spec.ts` 없음 — 코드 변경 없음
- Create: `docs/qa/2026-09-17-domain-cutover.md`

**Interfaces:**
- Consumes: 사용자가 Cloudflare Pages에서 custom domain을 Activate한 상태
- Produces: 두 출처가 모두 허용되는 API. Task 7의 canary가 `app` 출처로 검증한다.

**중요:** 순서를 지킨다. CORS에 새 출처를 **먼저** 넣고, 그 다음 도메인을 쓴다. 반대로 하면 새 주소에서 모든 API 호출이 막힌다.

- [ ] **Step 1: 현재 CORS 동작 확인**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X OPTIONS \
  -H "Origin: https://app.byguardian.site" \
  -H "Access-Control-Request-Method: PATCH" https://api.byguardian.site/me
```
Expected: `403` (아직 허용되지 않음). 이 값이 200이면 이미 적용된 것이므로 Step 3으로 간다.

- [ ] **Step 2: 두 출처를 함께 허용**

Task 1에서 찾은 env 파일에서 `NEXTVISIT_CORS_ORIGINS`를 `https://app.byguardian.site,https://byguardian.pages.dev`로 바꾸고 API만 재기동한다.

```bash
ssh llm 'cd <compose 경로> && docker compose up -d --no-deps api && docker compose ps'
```
Expected: api 컨테이너만 재생성되고 DB는 그대로 Up 상태.

- [ ] **Step 3: 두 출처 모두 통과 확인**

```bash
for o in https://app.byguardian.site https://byguardian.pages.dev; do
  printf "%s " "$o"
  curl -s -o /dev/null -w "%{http_code}\n" -X OPTIONS -H "Origin: $o" \
    -H "Access-Control-Request-Method: PATCH" \
    -H "Access-Control-Request-Headers: X-Guardian-Token" https://api.byguardian.site/me
done
curl -s -o /dev/null -w "evil %{http_code}\n" -X OPTIONS -H "Origin: https://app.byguardian.site.evil.invalid" \
  -H "Access-Control-Request-Method: PATCH" https://api.byguardian.site/me
```
Expected: 두 출처 모두 `200`, 유사 도메인은 `403`.

- [ ] **Step 4: 새 주소의 화면과 빌드 확인**

```bash
curl -s https://app.byguardian.site/build.json
curl -sI https://app.byguardian.site/ | grep -iE "^HTTP|content-security-policy"
```
Expected: `commit`이 `main`의 최신 커밋과 같고, CSP의 `connect-src`에 `https://api.byguardian.site`가 있다.

- [ ] **Step 5: 전환 기록 작성과 커밋**

`docs/qa/2026-09-17-domain-cutover.md`에 실행 시각, 위 명령의 실제 출력(상태 코드만), `pages.dev` 리다이렉트 규칙 적용 여부를 적는다.

```bash
git add docs/qa/2026-09-17-domain-cutover.md
git commit -m "docs: record the app domain cutover and CORS transition"
```

---

### Task 5: DB 백업과 실제 복원 시험

**Files:**
- Create: 운영 호스트의 백업 스크립트 `/opt/nextvisit/backup/pg-backup.sh` (Task 1에서 백업이 없다고 확인된 경우)
- Create: `infra/local/pg-backup.sh` (저장소에 보관하는 원본)
- Create: `docs/qa/2026-09-17-backup-restore.md`

**Interfaces:**
- Consumes: Task 1의 DB 컨테이너 이름과 볼륨 경로
- Produces: 완료 조건 5의 증거

- [ ] **Step 1: 백업 스크립트 작성**

`infra/local/pg-backup.sh`:

```bash
#!/bin/sh
# 운영 PostgreSQL의 논리 백업을 남긴다. 비밀값은 인자로 받지 않고 컨테이너 환경에서 읽는다.
set -eu
container=${1:?usage: pg-backup.sh <db-container> <out-dir>}
out=${2:?usage: pg-backup.sh <db-container> <out-dir>}
stamp=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$out"
docker exec "$container" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$out/nextvisit-$stamp.dump"
ls -l "$out/nextvisit-$stamp.dump"
```

- [ ] **Step 2: 백업 1회 실행**

```bash
scp infra/local/pg-backup.sh llm:/tmp/pg-backup.sh
ssh llm 'sudo install -m 0750 -o milo -g milo /tmp/pg-backup.sh /opt/nextvisit/backup/pg-backup.sh; \
  /opt/nextvisit/backup/pg-backup.sh <db-container> /opt/nextvisit/backup/out'
```
Expected: 0바이트가 아닌 `.dump` 파일 생성.

- [ ] **Step 3: 복원 시험 — 운영 DB를 건드리지 않는다**

임시 컨테이너에 복원해 행 수를 비교한다.

```bash
ssh llm 'docker run -d --name nextvisit-restore-test -e POSTGRES_PASSWORD=restoretest -e POSTGRES_USER=nextvisit -e POSTGRES_DB=nextvisit postgres:16; \
  sleep 10; \
  docker cp /opt/nextvisit/backup/out/<파일명> nextvisit-restore-test:/tmp/b.dump; \
  docker exec nextvisit-restore-test pg_restore -U nextvisit -d nextvisit /tmp/b.dump; \
  docker exec nextvisit-restore-test psql -U nextvisit -d nextvisit -c "select count(*) from cases;"'
```
Expected: 복원이 오류 없이 끝나고 `cases` 행 수가 운영과 같다. 운영 쪽 행 수는 같은 방식으로 따로 센다.

- [ ] **Step 4: 시험 컨테이너 정리**

```bash
ssh llm 'docker rm -f nextvisit-restore-test'
```
Expected: 임시 컨테이너만 삭제되고 운영 컨테이너는 그대로다. `docker ps`로 확인한다.

- [ ] **Step 5: 기록과 커밋**

`docs/qa/2026-09-17-backup-restore.md`에 백업 크기, 복원 소요 시간, 비교한 행 수, 사용한 명령을 적는다.

```bash
git add infra/local/pg-backup.sh docs/qa/2026-09-17-backup-restore.md
git commit -m "feat: add the production backup script and record a restore drill"
```

---

### Task 6: LLM 생성 타임아웃 해결과 폴백·재부팅 검증

**Files:**
- Modify: 운영 호스트의 API 환경변수 `NEXTVISIT_LLM_MODEL` 또는 `backend/api/src/main/java/nextvisit/api/llm/` 의 요청 구성
- Create: `docs/qa/2026-09-17-llm-activation.md`

**Interfaces:**
- Consumes: 위 실사표의 TIMEOUT 증거와 보유 모델 목록
- Produces: 완료 조건 7의 증거

**전제:** `NEXTVISIT_LLM_ENABLED`는 이미 `true`다. 문제는 켜는 것이 아니라 **3회 시도가 모두 45초 타임아웃으로 끝나 한 번도 성공하지 못한다는 것**이다. 유력한 원인은 `qwen3:4b`가 추론 모델이라 출력 예산을 사고 과정에 쓰고 `content`가 늦게·비어서 오는 것이다. 원인을 단정하지 말고 아래 순서로 좁힌다.

- [ ] **Step 1: 실제 프롬프트 형태로 재현**

`backend/api/src/main/java/nextvisit/api/llm/`에서 실제 요청 본문(시스템 프롬프트, 사용자 메시지, `max_tokens`)을 읽고, 같은 형태를 호스트에서 직접 호출해 시간을 잰다.

```bash
ssh llm 'time curl -s -m 180 http://127.0.0.1:11434/v1/chat/completions \
  -H "Content-Type: application/json" -d @/tmp/real-prompt.json -o /tmp/out.json -w "http=%{http_code}\n"; \
  python3 -c "import json;d=json.load(open(\"/tmp/out.json\"));m=d[\"choices\"][0][\"message\"];print(\"content_len\",len(m.get(\"content\") or \"\"),\"reasoning_len\",len(m.get(\"reasoning\") or \"\"))"'
```
Expected: 소요 시간과 `content`/`reasoning` 길이. `content_len`이 0이고 `reasoning_len`이 크면 추론 모델 가설이 확인된다.

- [ ] **Step 2: 후보 비교**

같은 프롬프트로 세 가지를 잰다. (1) `qwen3:4b-q4_K_M`에 추론 끄기(`/no_think` 또는 `chat_template_kwargs.enable_thinking=false`), (2) `qwen2.5:7b-instruct-q4_K_M`, (3) `qwen2.5:3b-instruct-q8_0`.

Expected: 45초 read timeout 안에 비어 있지 않은 `content`를 주는 후보. 각각의 소요 시간과 content 길이를 표로 적는다.

- [ ] **Step 3: 금지 표현 검사 통과 확인**

고른 후보의 실제 출력이 백엔드의 안전 검사를 통과하는지 확인한다.

Run: `cd backend && ./gradlew :api:test --tests "*Llm*" --tests "*Question*"`
Expected: 통과. 모델을 바꾸면 문장 품질이 달라지므로 이 검사가 회귀 방어선이다.

- [ ] **Step 4: 운영 적용과 비동기 다듬기 확인**

고른 설정을 운영에 적용하고 API만 재기동한다.

```bash
ssh llm 'cd <compose 경로> && docker compose up -d --no-deps api && sleep 20 && docker compose ps'
curl -s https://api.byguardian.site/health
```
Expected: `/health` 정상. 이어서 아래 검증을 한다.

새 합성 데모 케이스로 이번 주 기록을 저장한 뒤 진료 준비 카드를 두 번 조회한다(저장 직후, 60초 뒤).

Expected: 질문 문장이 템플릿과 달라지고 `source`가 `LLM`으로 바뀐다. 문장 자체는 보고서에 옮기지 않고 `source` 분포와 소요 시간만 적는다. 금지 표현 검사는 백엔드 테스트가 이미 담당한다.

- [ ] **Step 5: 폴백 확인 — 이것이 통과해야 활성화를 유지한다**

```bash
ssh llm 'cd <compose 경로> && docker compose stop ollama'
```
그 상태에서 기록 저장과 진료 준비 카드 조회를 한 번 더 한다.

Expected: 저장은 200으로 성공하고, 질문이 전부 `TEMPLATE`로 나온다. 화면에 오류가 뜨지 않는다. 확인 뒤 `docker compose start ollama`로 되돌린다. 이 검사가 실패하면 `NEXTVISIT_LLM_ENABLED=false`로 즉시 되돌리고 원인을 적는다.

- [ ] **Step 6: 재부팅 복구 확인**

```bash
ssh llm 'sudo systemctl reboot'
```
5분 뒤 `curl -s https://api.byguardian.site/health`와 `ssh llm 'docker ps'`로 네 컨테이너와 cloudflared 복구를 확인한다. Expected: 수동 조치 없이 전부 Up, `/health` 정상.

- [ ] **Step 7: 기록과 커밋**

```bash
git add docs/qa/2026-09-17-llm-activation.md
git commit -m "docs: record LLM activation, fallback, and reboot recovery"
```

---

### Task 6-2: 운영 백엔드를 `main` 최신으로 재배포

**Files:**
- Modify: 운영 호스트의 `nextvisit-api:demo` 이미지
- Create: `docs/qa/2026-09-17-backend-redeploy.md`

**Interfaces:**
- Consumes: Task 5의 백업(재배포 전 반드시 1회 확보)
- Produces: 운영 API가 `main`과 같은 코드임을 보이는 증거

**왜 필요한가:** 운영 이미지는 2026-09-14 빌드라 `a3ff703`(지난 날짜를 다음 진료일로 저장하지 못하게 하는 수정)이 빠져 있다. 프런트엔드만 최신이라 FE/BE가 어긋나 있다.

- [ ] **Step 1: 재배포 전 결함 재현**

합성 데모 케이스의 보호자 토큰으로 지난 날짜를 저장해 본다.

```bash
curl -s -X PATCH https://api.byguardian.site/me -H "Content-Type: application/json" \
  -H "X-Guardian-Token: <합성 케이스 토큰>" -d '{"nextVisitDate":"2020-01-01"}' -w "\n%{http_code}\n"
```
Expected: 재배포 전에는 `200`(결함 재현). 재배포 뒤에는 `400`이어야 한다.

- [ ] **Step 2: 백업 확보 확인**

Task 5의 덤프가 있는지 확인한다. 없으면 먼저 Task 5를 끝낸다. Expected: 0바이트가 아닌 최근 덤프 파일.

- [ ] **Step 3: 최신 소스로 이미지 빌드**

운영 호스트의 `/home/nextvisit-runner/ByGuardian`를 `origin/main`으로 갱신한 뒤 데모 스택의 Dockerfile로 다시 빌드한다. 빌드는 API만 대상으로 한다.

```bash
ssh llm 'cd <ByGuardian 경로> && git fetch origin && git log --oneline -1 origin/main && git checkout -f origin/main'
ssh llm 'cd <demo compose 경로> && docker compose build api && docker compose up -d --no-deps api'
```
Expected: 빌드 성공, api 컨테이너만 교체, postgres는 그대로 Up.

- [ ] **Step 4: 재배포 검증**

```bash
curl -s https://api.byguardian.site/health
```
그리고 Step 1의 PATCH를 다시 실행한다.

Expected: `/health` 정상이고 지난 날짜 저장이 `400`으로 거부된다. 기존 합성 케이스의 기록과 치료사 링크가 그대로 열린다.

- [ ] **Step 5: 기록과 커밋**

`docs/qa/2026-09-17-backend-redeploy.md`에 재배포 전후 커밋 SHA, 상태 코드, 검증 시각을 적는다.

```bash
git add docs/qa/2026-09-17-backend-redeploy.md
git commit -m "docs: record the production backend redeploy to main"
```

---

### Task 7: 운영 canary와 최종 QA 보고서

**Files:**
- Create: `docs/qa/2026-09-17-production-acceptance.md`
- Modify: `README.md` (Task 3에서 만든 운영 상태 표의 LLM 줄과 확인 날짜)

**Interfaces:**
- Consumes: Task 1~6의 증거 문서
- Produces: 완료 조건 1~8 각각에 대한 판정

- [ ] **Step 1: 새 주소에서 핵심 흐름 재실행**

`app.byguardian.site`를 기준으로 데모 생성 → 주간 기록 저장 → 진료 준비 카드 → 전체 기록 → 치료사 공유 → 이어받기를 실행한다. 375px와 1280px 모두 확인한다.

Expected: 모든 단계 성공, JS 오류 없음, 제3자 출처 요청 없음, fragment 토큰이 문서 요청에 없음.

- [ ] **Step 2: 완료 조건 판정표 작성**

완료 조건 1~8을 행으로 두고 각 행에 판정, 근거 문서, 확인 명령을 적는다. 통과하지 못한 항목은 그대로 "미통과"로 적고 이유를 쓴다.

- [ ] **Step 3: 남은 한계 적기**

실사용자 연구 없음, 실기기·타 브라우저·스크린 리더 시험 없음, 단일 호스트라 하드웨어 장애 시 서비스 전체 중단, 운영 DB에 합성 데모 데이터 존재, 개인정보 보유·삭제 정책 미구현을 적는다.

- [ ] **Step 4: 전체 검증 재실행**

```bash
export PATH="$HOME/.nvm/versions/node/v22.22.2/bin:$PATH"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
npm --prefix frontend test && npm --prefix frontend run typecheck && npm --prefix frontend run build
(cd backend && ./gradlew clean test)
bash scripts/ci/integration-e2e.sh
```
Expected: FE 275 테스트, 백엔드 270 테스트, 통합 13 테스트가 모두 통과.

- [ ] **Step 5: 커밋과 PR**

```bash
git add docs/qa README.md
git commit -m "docs: accept the single-host production deployment"
git push -u origin <branch>
gh pr create --base main --title "docs: 단일 호스트 운영 기준 정리와 활성화 증거" --body-file <본문>
```

---

## Self-Review

**Spec coverage:** Task 2가 새 설계서를 만들고 완료 조건 1~8을 정의한다. 조건 1은 Task 2·3, 조건 2는 Task 7 Step 4, 조건 3은 Task 4, 조건 4는 Task 1 Step 4와 Task 7, 조건 5는 Task 5, 조건 6은 Task 6 Step 6, 조건 7은 Task 6 Step 5, 조건 8은 모든 Task의 "값을 적지 않는다" 규칙과 Task 7 Step 2가 다룬다.

**미해결로 남기는 것:** `generationStatus` 필드는 새 설계의 범위 밖으로 명시한다(Task 2 Step 1의 5절). 운영 DB의 합성 데모 데이터 삭제는 개인정보·보유 정책 구현이 소유하므로 이번 범위 밖이며 Task 7 Step 3에 한계로 적는다.

**의존 순서:** Task 1 → 2 → 3은 순차. Task 4는 사용자의 Pages custom domain 활성화 이후. Task 5는 Task 1 이후 독립 실행 가능. Task 6은 Task 1 이후이며 재부팅을 포함하므로 Task 4·5가 끝난 뒤에 한다. Task 7이 마지막이다.
