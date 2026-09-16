#!/bin/sh
# 운영 PostgreSQL의 논리 백업(pg_dump custom format)을 남긴다.
# 비밀값(DB 비밀번호 등)은 인자나 커맨드라인으로 받지 않고,
# 컨테이너 안에서 환경변수($POSTGRES_USER, $POSTGRES_DB)로만 읽는다.
#
# 사용법:
#   ./pg-backup.sh <db-container> <out-dir>
#
# 예:
#   ./pg-backup.sh nextvisit-demo-postgres-1 /home/milo/nextvisit-backups/out
#
# 결과: <out-dir>/nextvisit-<UTC타임스탬프>.dump (pg_restore로 복원 가능한 custom 포맷)
set -eu
container=${1:?usage: pg-backup.sh <db-container> <out-dir>}
out=${2:?usage: pg-backup.sh <db-container> <out-dir>}
stamp=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$out"
docker exec "$container" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$out/nextvisit-$stamp.dump"
ls -l "$out/nextvisit-$stamp.dump"
