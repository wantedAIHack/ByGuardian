# 단일 호스트 운영 실사 보고서

실사일: 2026-09-17 · 대상 호스트: `llm` (SSH alias, 사용자 `milo`, docker 그룹 소속)

## 범위와 방법

`ssh llm '<command>'`로 접속해 읽기 전용 명령만 실행했다. `docker ...`는 `milo`가 docker 그룹에 속해 있어 sudo 없이 실행된다. `sudo`가 필요한 명령(`sudo crontab -l`, `sudo ls /home/nextvisit-runner` 등)은 비밀번호가 없어 실행하지 못했고, 이런 항목은 표에 "sudo 필요 — 확인 못 함"으로 표시했다. 컨테이너를 시작·중지·재시작·빌드·삭제하는 명령은 실행하지 않았다. **환경변수는 이름만 수집했고 값은 어떤 명령에서도 출력·기록하지 않았다.**

저장소 안의 `infra/demo/compose.demo.yml`, `infra/llm/compose.yml`은 참고용으로 함께 읽었다. 실제 배포 호스트의 `/home/nextvisit-runner/demo/compose.demo.yml`과 `/home/nextvisit-runner/ByGuardian/infra/llm/compose.yml`은 해당 디렉터리가 `nextvisit-runner` 소유(권한 750)라 `milo`로는 열람할 수 없었다. 즉 이 문서의 compose 설정 설명은 "저장소에 있는 동일 이름 파일"을 근거로 하며, 배포 호스트의 실제 파일 내용과 바이트 단위로 같다는 것까지 확인한 것은 아니다.

## 컨테이너 인벤토리

`docker ps` 및 `docker compose ls` 결과: 실행 중인 compose 프로젝트는 2개다.

| 프로젝트 | 상태 | 구성 파일 경로(호스트 기준) |
| --- | --- | --- |
| `nextvisit-demo` | running(2) | `/home/nextvisit-runner/demo/compose.demo.yml` |
| `nextvisit-llm` | running(1) | `/home/nextvisit-runner/ByGuardian/infra/llm/compose.yml`, `.../compose.gpu.yml` |

확인 명령: `ssh llm 'docker compose ls'`

| 컨테이너 | 이미지 | 재시작 정책 | 볼륨(호스트→컨테이너) |
| --- | --- | --- | --- |
| `nextvisit-demo-api-1` | `nextvisit-api:demo` | `unless-stopped` | 없음(코드 빌드 이미지, 마운트 볼륨 없음) |
| `nextvisit-demo-postgres-1` | `postgres:16-alpine@sha256:cf78e7...` | `unless-stopped` | `/var/lib/docker/volumes/nextvisit-demo_demo-pg/_data` → `/var/lib/postgresql/data` |
| `nextvisit-llm-ollama-1` | `nextvisit-ollama:0.33.3` | `unless-stopped` | `/var/lib/docker/volumes/nextvisit-llm-ollama-data/_data` → `/root/.ollama` |

확인 명령:
```
ssh llm 'docker inspect --format "{{.Name}} {{.Config.Image}} {{.HostConfig.RestartPolicy.Name}}" $(docker ps -q); \
  docker inspect --format "{{range .Mounts}}{{.Type}} {{.Source}} -> {{.Destination}}{{println}}{{end}}" $(docker ps -q)'
```

## API 컨테이너 환경변수(이름만)

`nextvisit-demo-api-1`에 설정된 환경변수 이름 목록이다. 값은 확인하지 않았다.

```
JAVA_HOME
JAVA_VERSION
LANG
LANGUAGE
LC_ALL
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
PATH
SERVER_ADDRESS
SERVER_PORT
```

`JAVA_*`, `LANG*`, `PATH`는 베이스 이미지가 설정하는 값이고 나머지는 `compose.demo.yml`에서 온다. `NEXTVISIT_DB_PASSWORD`, `NEXTVISIT_CORS_ORIGINS`는 저장소의 `compose.demo.yml`에서 `${...:?Set ...}` 형태로 필수 지정되어 있으므로, 실제 값은 배포 호스트의 `.env` 파일이나 셸 환경에 있을 것으로 추정되나 그 파일 위치와 값은 확인하지 못했다(`sudo 필요 — 확인 못 함`, 저장소 경로 추정치는 `/home/nextvisit-runner/demo/.env`).

확인 명령: `ssh llm 'docker inspect --format "{{range .Config.Env}}{{println .}}{{end}}" $(docker ps -qf name=api | head -1) | cut -d= -f1 | sort'`

## Ollama 모델과 GPU 사용

`nextvisit-llm-ollama-1` 컨테이너에 받아져 있는 모델 태그:

| 모델 태그 | 크기 | 최근 수정 |
| --- | --- | --- |
| `qwen3:4b-q4_K_M` | 2.6 GB | 47시간 전 |
| `qwen2.5:7b-instruct-q4_K_M` | 4.7 GB | 47시간 전 |
| `qwen2.5:3b-instruct-q8_0` | 3.3 GB | 47시간 전 |
| `qwen3:4b-q8_0` | 4.4 GB | 2일 전 |

실사 시점에 로드되어 있던 모델(`ollama ps`): `qwen3:4b-q4_K_M`, 프로세서 `100% GPU`, 컨텍스트 8192. `compose.demo.yml`의 `NEXTVISIT_LLM_MODEL` 기본값과 일치한다.

> **이 8192는 특정 시점의 관측값이다.** 호스트 시각 2026-09-16 16:40 UTC까지 실행 중이던 `nextvisit-llm-ollama-1` 컨테이너의 값이다. 같은 날 16:49:35 UTC에 이 컨테이너가 재생성되면서(무엇이 또는 누가 했는지는 확인되지 않았다) `OLLAMA_CONTEXT_LENGTH=2048`로 바뀌었고, 그 상태에서는 질문 다듬기 응답 스키마가 깨진다. 경위와 영향은 [`2026-09-17-llm-activation.md`](./2026-09-17-llm-activation.md) 3절에 있다. 지금 값을 알아야 하면 위 확인 명령 대신 `docker inspect`로 직접 읽어야 한다.

확인 명령:
```
ssh llm 'docker exec $(docker ps -qf name=ollama | head -1) ollama list; \
  docker exec $(docker ps -qf name=ollama | head -1) ollama ps'
```

## Cloudflare Tunnel

`cloudflared`는 호스트 systemd 서비스로 실행 중이다(`docker` 컨테이너가 아님).

- 서비스 상태: `active (running)`, `enabled`(부팅 시 자동 시작)
- 실행 명령: `/usr/bin/cloudflared --no-autoupdate tunnel run --token-file /etc/cloudflared/token`
- `/etc/cloudflared` 디렉터리는 `root:root`, 권한 `755`라 목록은 `sudo` 없이도 보였다. 안에는 `token` 파일 하나가 있고 권한은 `600`(root만 읽기)이라 **내용은 확인하지 않았다**(확인할 필요도, 권한도 없음).
- precheck 로그상 DNS·UDP·TCP 연결, Cloudflare API 접근이 모두 `pass`로 나왔다.

확인 명령: `ssh llm 'systemctl status cloudflared --no-pager'`, `ssh llm 'ls -la /etc/cloudflared'`

## 백업 존재 여부

다음 경로·설정에서 백업 관련 흔적을 찾지 못했다.

| 확인 대상 | 결과 | 확인 명령 |
| --- | --- | --- |
| `milo` 사용자 crontab | 없음(`no crontab for milo`) | `ssh llm 'crontab -l'` |
| `root` crontab | 확인 못 함(sudo 필요) | `ssh llm 'sudo crontab -l'` |
| `/var/lib/nextvisit/backups` | 경로 자체가 없음 | `ssh llm 'ls -la /var/lib/nextvisit/backups'` |
| `/opt/nextvisit/backups` | 경로 자체가 없음 | `ssh llm 'ls -la /opt/nextvisit/backups'` |
| `/opt/nextvisit/llm` | `nextvisit-runner` 소유(750)라 목록 조회 불가 | `ssh llm 'ls -la /opt/nextvisit/llm'` |
| `find / -iname "*backup*"` (4단계 깊이) | 시스템 기본 `/var/backups`, `dpkg-db-backup`, `vgcfgbackup`뿐, nextvisit 관련 항목 없음 | `ssh llm 'find / -maxdepth 4 -iname "*backup*"'` |

**결론: 이 호스트에서 자동 백업(DB 덤프 등)이 동작하고 있다는 증거를 찾지 못했다.** 다만 `root` crontab과 `/home/nextvisit-runner`, `/opt/nextvisit/llm` 내부는 권한상 들여다보지 못했으므로, 백업이 그 안에 root 권한으로만 존재할 가능성은 배제하지 못한다. 확실한 결론을 원하면 root 권한으로 위 두 항목을 추가 확인해야 한다.

## 포트 노출 (loopback 전용 확인)

`ss -tlnp` 결과, docker가 게시한 서비스 포트는 모두 `127.0.0.1`에만 바인딩되어 있고 `0.0.0.0`이나 외부 인터페이스에는 없다.

| 포트 | 바인딩 | 비고 |
| --- | --- | --- |
| 8080 | `127.0.0.1` | `nextvisit-demo-api-1` (API) |
| 11434 | `127.0.0.1` | `nextvisit-llm-ollama-1` (Ollama) |
| 22 | `0.0.0.0`, `[::]` | SSH, 의도된 외부 노출 |
| 53 | `127.0.0.54`, `127.0.0.53%lo` | 로컬 systemd-resolved |
| 20241 | `127.0.0.1` | 프로세스명 미확인(권한 부족으로 소유 프로세스 확인 못 함). nextvisit 관련 컨테이너 포트 목록(`docker ps -a`)에는 없음 |

`docker ps -a --format "{{.Ports}}"` 로 대조한 결과, PostgreSQL 컨테이너(`nextvisit-demo-postgres-1`)는 호스트에 포트를 게시하지 않는다(컨테이너 내부 5432만 노출, docker 네트워크 내부에서만 접근 가능). API와 Ollama 외에 LAN·외부에 노출된 nextvisit 관련 포트는 없다.

확인 명령: `ssh llm 'ss -tlnp'`, `ssh llm 'docker ps -a --format "{{.Names}}\t{{.Ports}}"'`

## GPU

- 모델: NVIDIA GeForce GTX 1060
- 드라이버 버전: 580.173.02

확인 명령: `ssh llm 'nvidia-smi --query-gpu=name,driver_version --format=csv,noheader'`

## 확인하지 못한 항목 요약

- 배포 호스트의 `compose.demo.yml`, `compose.yml` 실제 파일 내용(권한 없음, 저장소 사본으로 대체 확인)
- `NEXTVISIT_DB_PASSWORD`, `NEXTVISIT_CORS_ORIGINS` 등 환경변수 값과 정확한 출처 파일(sudo 필요 — 확인 못 함)
- `root` crontab (sudo 필요 — 확인 못 함)
- `/home/nextvisit-runner` 홈 디렉터리 내용 (권한 거부)
- `/opt/nextvisit/llm` 내부 파일 (권한 거부)
- 포트 20241의 소유 프로세스 (프로세스 이름 조회에 권한 필요)
