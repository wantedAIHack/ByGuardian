# DB 백업 생성 및 복원 시험 보고서

시험일: 2026-09-17 · 대상 호스트: `llm` (SSH alias, 사용자 `milo`, docker 그룹 소속) · 대상 DB 컨테이너: `nextvisit-demo-postgres-1` (`postgres:16-alpine`, DB `nextvisit`)

## 요약

운영 PostgreSQL 컨테이너에서 논리 백업(`pg_dump -Fc`)을 1회 실행하고, 별도의 임시 컨테이너에 그 덤프를 복원한 뒤 운영 DB와 행 수를 표 단위로 비교했다. 6개 테이블 전부 운영과 복원본의 행 수가 정확히 일치했고, 복원은 오류 없이(exit code 0) 약 0.24초 만에 끝났다. 시험이 끝난 뒤 임시 컨테이너는 삭제했고 운영 컨테이너 3개는 시험 시작 전과 동일하게 계속 실행 중이다.

## 설치 경로 이탈 사항 (컨트롤러 지시에 따른 변경)

작업 브리프는 스크립트를 `/opt/nextvisit/backup/pg-backup.sh`에 설치하라고 지시했다. 그러나 이 경로는 root 소유이며 설치하려면 `sudo install -m 0750 -o milo -g milo ...`가 필요한데, 이 작업을 수행하는 에이전트는 `sudo` 비밀번호를 갖고 있지 않다. 컨트롤러 지시에 따라 대신 `milo`가 소유한 다음 경로를 사용했다.

- 스크립트: `/home/milo/nextvisit-backups/pg-backup.sh`
- 덤프 출력 디렉터리: `/home/milo/nextvisit-backups/out/`

**주의:** 이 경로는 `milo` 계정 소유이며 root 소유가 아니다. 운영자가 이후 `sudo`로 `/opt/nextvisit/backup/`(또는 다른 root 소유 경로)로 옮기고 싶다면 별도로 이관 작업을 해야 한다. 저장소에 보관하는 원본(`infra/local/pg-backup.sh`)은 브리프가 요구한 내용 그대로이며 특정 설치 경로를 하드코딩하지 않으므로(컨테이너명과 출력 디렉터리를 인자로 받음) 이관 시 스크립트 자체는 수정할 필요가 없다.

## 방법

`ssh llm '<command>'`로 접속해 다음 순서로 진행했다. 운영 컨테이너(`nextvisit-demo-postgres-1`, `nextvisit-demo-api-1`)에 대해서는 **읽기 전용 명령**(`docker exec ... pg_dump`, `docker exec ... psql -c "select count(*) ..."`)만 실행했다. 운영 컨테이너를 중지·재시작·재빌드·삭제하는 명령이나 `docker compose down`, `--volumes`가 붙은 명령은 실행하지 않았다. 운영 DB에 대한 쓰기·DDL도 실행하지 않았다. 비밀번호는 컨테이너 내부에서 `$POSTGRES_USER`, `$POSTGRES_DB` 환경변수로만 읽었고(`docker exec <컨테이너> sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc'`), 어떤 명령줄·로그·이 문서에도 실제 비밀값을 출력하지 않았다. 복원 시험용 임시 컨테이너에는 운영과 무관한 가짜 비밀번호(`throwaway-not-real-pw`)를 사용했다.

### 1. 백업 스크립트 작성 및 배포

저장소 원본: `infra/local/pg-backup.sh` (mode 755, `set -eu`, POSIX `sh`, shellcheck 통과).

```
scp infra/local/pg-backup.sh llm:/home/milo/nextvisit-backups/pg-backup.sh
ssh llm 'chmod 755 /home/milo/nextvisit-backups/pg-backup.sh'
```

### 2. 백업 1회 실행

```
ssh llm '/home/milo/nextvisit-backups/pg-backup.sh nextvisit-demo-postgres-1 /home/milo/nextvisit-backups/out'
```

결과:

```
-rw-rw-r-- 1 milo milo 28240 Sep 16 16:15 /home/milo/nextvisit-backups/out/nextvisit-20260916T161551Z.dump
```

- **덤프 크기: 28,240 바이트 (약 27.6 KiB)** — 0바이트가 아닌 정상 파일.
- 덤프 파일명의 타임스탬프는 UTC 기준 `20260916T161551Z`(= 2026-09-17 KST 01:15:51경)이다. 호스트 시스템 시각이 UTC라 브리프의 작업일(2026-09-17)과 하루 차이로 보일 수 있으나 같은 실행이다.

### 3. 복원 시험 (운영 DB는 건드리지 않음)

운영과 완전히 분리된 임시 컨테이너를 새로 띄우고, 거기에만 복원했다.

```
ssh llm 'docker run -d --name nextvisit-restore-test \
    -e POSTGRES_PASSWORD=throwaway-not-real-pw \
    -e POSTGRES_USER=nextvisit -e POSTGRES_DB=nextvisit postgres:16-alpine'
# pg_isready로 기동 대기
ssh llm 'docker cp /home/milo/nextvisit-backups/out/nextvisit-20260916T161551Z.dump nextvisit-restore-test:/tmp/b.dump'
ssh llm '/usr/bin/time -f "restore_wall_seconds=%e" \
    docker exec nextvisit-restore-test pg_restore -U nextvisit -d nextvisit --clean --if-exists /tmp/b.dump'
```

결과: `pg_restore` exit code 0 (오류 없음). **복원 소요 시간: 약 0.24초** (`restore_wall_seconds=0.24`, `/usr/bin/time` 실측). 데이터 규모가 작아(전체 6개 테이블 합계 약 200행) 매우 빠르게 끝났다.

복원본에서 `\dt`로 테이블 목록을 확인해 실제 테이블 이름을 얻었다(추측하지 않고 직접 조회):

```
 public | cases                 | table | nextvisit
 public | flyway_schema_history | table | nextvisit
 public | guardians             | table | nextvisit
 public | question_cache        | table | nextvisit
 public | snapshots             | table | nextvisit
 public | therapist_links       | table | nextvisit
```

이 스키마에서 주간 기록에 해당하는 테이블은 `snapshots`, 질문(문항) 관련 테이블은 `question_cache`이다. `weekly_records`, `questions`라는 이름의 테이블은 존재하지 않았다(스키마에 맞춰 실제 이름을 사용함).

### 4. 행 수 비교 (운영 vs 복원본)

운영 쪽은 백업 실행 전후로 각각 조회했고, 복원 직후 두 번째 조회(백업 이후 재확인)에서도 값이 동일했다 — 시험 도중 운영 데이터에 변화가 없었음을 의미한다.

| 테이블 | 운영(`nextvisit-demo-postgres-1`) | 복원본(`nextvisit-restore-test`) | 일치 여부 |
| --- | --- | --- | --- |
| `cases` | 22 | 22 | 일치 |
| `flyway_schema_history` | 2 | 2 | 일치 |
| `guardians` | 24 | 24 | 일치 |
| `question_cache` (질문/문항) | 20 | 20 | 일치 |
| `snapshots` (주간 기록) | 112 | 112 | 일치 |
| `therapist_links` | 25 | 25 | 일치 |

확인 명령(테이블마다 반복):

```
ssh llm "docker exec nextvisit-demo-postgres-1 sh -c 'psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tA -c \"select count(*) from <table>;\"'"
ssh llm "docker exec nextvisit-restore-test psql -U nextvisit -d nextvisit -tA -c \"select count(*) from <table>;\""
```

**6개 테이블 전부 운영과 복원본의 행 수가 정확히 일치했다.**

### 5. 임시 컨테이너 정리

```
ssh llm 'docker rm -f nextvisit-restore-test'
```

정리 후 `docker ps -a` 확인 결과, 운영 컨테이너 3개(`nextvisit-demo-api-1`, `nextvisit-llm-ollama-1`, `nextvisit-demo-postgres-1`)만 남아 있고 모두 시험 시작 전과 동일하게 `Up`(정상 실행 중, postgres는 healthy) 상태였다. `nextvisit-restore-test`는 목록에서 사라졌다. 호스트에 남긴 파일은 스크립트(`/home/milo/nextvisit-backups/pg-backup.sh`)와 덤프(`/home/milo/nextvisit-backups/out/nextvisit-20260916T161551Z.dump`) 두 개뿐이며, 둘 다 의도한 산출물이다. `/tmp` 등에 남긴 임시 파일은 없다.

## 이 시험이 증명하는 것과 증명하지 못하는 것 (한계)

이 시험이 증명하는 것:

- `pg_dump -Fc`로 뜬 논리 백업 1개가, 신선한(fresh) PostgreSQL 16 컨테이너에 오류 없이 복원되며, 운영 DB의 모든 테이블·행 수와 정확히 일치한다는 것.
- 백업/복원 절차(스크립트, 명령)가 실제로 동작한다는 것.
- 운영 컨테이너를 전혀 건드리지 않고 이 시험을 수행할 수 있다는 것.

이 시험이 증명하지 **못하는** 것:

- **시점 복구(PITR)**: WAL 아카이빙을 하지 않으므로 "백업 시점"과 "장애 발생 시점" 사이의 데이터는 복구할 수 없다. 이 방식으로 되돌릴 수 있는 시점은 마지막 `pg_dump` 실행 시점뿐이다.
- **디스크 장애 복구**: 이 시험은 같은 호스트, 같은 디스크 위에서 컨테이너만 새로 띄워 복원한 것이다. 호스트 디스크 자체가 손상되는 시나리오(볼륨 `nextvisit-demo_demo-pg` 자체가 사라지는 경우)에서 백업 파일이 같은 디스크에만 있다면 백업도 함께 유실된다. 오프사이트(원격지) 백업 보관은 이번 시험 범위가 아니며, 현재 `/home/milo/nextvisit-backups/out/`은 운영 DB와 **같은 호스트**에 있다.
- **정기 백업 스케줄**: 호스트에 확인해본 결과 `milo` 계정에는 사용자 crontab이 없었고(`crontab -l` → "no crontab for milo"), 이 백업을 위한 systemd 타이머도 없다. **현재 이 백업 스크립트를 자동으로 반복 실행하는 스케줄은 존재하지 않는다.** 이번에 만든 것은 수동 실행 스크립트와 1회성 실행 증거이며, 정기 실행(cron/systemd timer 등)은 별도 작업으로 구성해야 한다.
- **애플리케이션 정합성**: 데이터베이스 행 수 일치만 확인했고, API 서버(`nextvisit-demo-api-1`)가 복원본을 정상적으로 읽고 쓸 수 있는지, 애플리케이션 레벨 무결성(외래키 밖의 참조, 캐시 정합성 등)까지는 검증하지 않았다.
- **대용량 데이터에서의 소요 시간**: 이번 운영 DB는 매우 작다(6개 테이블 합계 약 200행, 덤프 28KB). 실제 프로덕션 규모로 데이터가 커지면 백업/복원 소요 시간과 디스크 사용량은 이번 수치와 크게 달라질 수 있다.
