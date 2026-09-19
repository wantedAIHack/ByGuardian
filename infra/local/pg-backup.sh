#!/bin/sh
# 운영 PostgreSQL의 논리 백업(pg_dump custom format)을 남긴다.
# 비밀값(DB 비밀번호 등)은 인자나 커맨드라인으로 받지 않고,
# 컨테이너 안에서 환경변수($POSTGRES_USER, $POSTGRES_DB)로만 읽는다.
#
# 덤프에는 보호자·치료사 등 개인정보가 포함되므로, 출력 디렉터리는 700,
# 덤프 파일은 600으로 만들어 소유자 외에는 읽지 못하게 한다(umask로 강제).
# 또한 pg_dump가 실패하거나 결과가 비어 있으면 완성된 것처럼 보이는
# 파일을 남기지 않는다(임시 파일에 쓴 뒤 검증하고 나서야 최종 이름으로 옮김).
#
# 사용법:
#   ./pg-backup.sh <db-container> <out-dir>
#
# 예:
#   ./pg-backup.sh nextvisit-demo-postgres-1 /home/milo/nextvisit-backups/out
#
# 결과: <out-dir>/nextvisit-<UTC타임스탬프>.dump (pg_restore로 복원 가능한 custom 포맷)
set -eu
umask 077
container=${1:?usage: pg-backup.sh <db-container> <out-dir>}
out=${2:?usage: pg-backup.sh <db-container> <out-dir>}
stamp=$(date -u +%Y%m%dT%H%M%SZ)

mkdir -p "$out"
chmod 700 "$out"

final="$out/nextvisit-$stamp.dump"
tmp="$out/.nextvisit-$stamp.dump.tmp.$$"
trap 'rm -f "$tmp"' EXIT INT TERM

docker exec "$container" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$tmp"

if [ ! -s "$tmp" ]; then
    echo "backup failed: dump is empty, not keeping it" >&2
    exit 1
fi

chmod 600 "$tmp"
mv "$tmp" "$final"
ls -l "$final"
