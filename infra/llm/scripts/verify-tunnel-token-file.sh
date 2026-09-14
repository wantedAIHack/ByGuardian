#!/bin/sh
set -eu

path="${1:?Usage: verify-tunnel-token-file.sh <absolute-path-to-tunnel-token-file>}"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

case "$path" in
  /*) ;;
  *) fail "tunnel token file path must be absolute" ;;
esac

# Numeric owner:group:mode without dereferencing symlinks (lstat semantics on
# both GNU and BSD stat implementations).
meta() {
  if out="$(stat -c '%u %g %a' -- "$1" 2>/dev/null)"; then
    printf '%s\n' "$out"
    return 0
  fi
  stat -f '%u %g %Lp' -- "$1"
}

parent="$(dirname -- "$path")"

[ -L "$parent" ] && fail "tunnel token file parent must not be a symlink"
[ -d "$parent" ] || fail "tunnel token file parent must be a directory"

parent_meta="$(meta "$parent")"
parent_uid="${parent_meta%% *}"
parent_rest="${parent_meta#* }"
parent_gid="${parent_rest%% *}"
parent_mode="${parent_rest#* }"

[ "$parent_uid" = 0 ] || fail "tunnel token file parent must be owned by root"
[ "$parent_gid" = 65532 ] || fail "tunnel token file parent group must be numeric 65532"
[ "$parent_mode" = 750 ] || fail "tunnel token file parent mode must be 0750"

[ -L "$path" ] && fail "tunnel token file must not be a symlink"
[ -e "$path" ] || fail "tunnel token file must exist"
[ -f "$path" ] || fail "tunnel token file must be a regular file"

file_meta="$(meta "$path")"
file_uid="${file_meta%% *}"
file_rest="${file_meta#* }"
file_gid="${file_rest%% *}"
file_mode="${file_rest#* }"

[ "$file_uid" = 0 ] || fail "tunnel token file must be owned by root"
[ "$file_gid" = 65532 ] || fail "tunnel token file group must be numeric 65532"
[ "$file_mode" = 440 ] || fail "tunnel token file mode must be 0440"

size="$(wc -c <"$path" | tr -d ' ')"
case "$size" in
  ''|*[!0-9]*) fail "tunnel token file size could not be determined" ;;
esac
[ "$size" -ge 1 ] || fail "tunnel token file must not be empty"
[ "$size" -le 4096 ] || fail "tunnel token file must be at most 4096 bytes"

newline_count="$(LC_ALL=C tr -dc '\n' <"$path" | wc -c | tr -d ' ')"
[ "$newline_count" = 1 ] || fail "tunnel token file must contain exactly one LF-terminated line"
[ "$size" -gt "$newline_count" ] || fail "tunnel token file line must not be empty"

last_byte="$(tail -c 1 "$path" | od -An -tx1 | tr -d ' \n')"
[ "$last_byte" = "0a" ] || fail "tunnel token file must end with a single LF"

forbidden_bytes="$(LC_ALL=C tr -dc '\r\000' <"$path" | wc -c | tr -d ' ')"
[ "$forbidden_bytes" = 0 ] || fail "tunnel token file must not contain CR or NUL bytes"

printf '%s\n' "tunnel token file verified"
