#!/usr/bin/env bash
# 시연 시드 실행기 — 시연 전용 DB가 아니면 실행을 거부한다.
# 사용 (Git Bash, chagok-backend 루트에서):
#   DEMO_DB_USER=chagok bash db/seed/run-demo-seed.sh
# 환경변수: DEMO_DB_NAME(기본 chagok_demo) DEMO_DB_HOST(기본 127.0.0.1) DEMO_DB_PORT(기본 3306) DEMO_DB_USER(필수)
# 비밀번호는 mysql이 직접 묻는다(명령줄·파일에 남기지 않음). MYSQL_PWD를 쓰면 묻지 않는다.
set -euo pipefail

DB_NAME="${DEMO_DB_NAME:-chagok_demo}"
HOST="${DEMO_DB_HOST:-127.0.0.1}"
PORT="${DEMO_DB_PORT:-3306}"
USER_NAME="${DEMO_DB_USER:?DEMO_DB_USER를 지정하세요}"
SEED_FILE="$(cd "$(dirname "$0")" && pwd)/demo_seed.sql"

fail() { echo "거부: $1" >&2; exit 1; }

case "$DB_NAME" in
  *_demo) ;;
  *) fail "시연 DB 이름은 '_demo'로 끝나야 합니다 (지금: $DB_NAME)" ;;
esac

# 실행할 문장이 있는지 (주석·빈 줄만이면 아직 작성 전)
if ! grep -Ev '^\s*(--.*)?$' "$SEED_FILE" | grep -q .; then
  fail "demo_seed.sql이 아직 비어 있습니다 (ERD 확정 뒤 DB 담당자가 작성)"
fi
# 데이터를 지우거나 구조를 바꾸는 문장은 허용하지 않는다
if grep -Eiq '^\s*(delete|truncate|drop|alter|create|update)\b' "$SEED_FILE"; then
  fail "demo_seed.sql에 DELETE·TRUNCATE·DROP·ALTER·CREATE·UPDATE 문장이 있습니다"
fi

read -r -s -p "MySQL 비밀번호 ($USER_NAME@$HOST): " PW; echo
export MYSQL_PWD="$PW"
q() { mysql -h "$HOST" -P "$PORT" -u "$USER_NAME" -N -B "$DB_NAME" -e "$1"; }

[ "$(q 'SELECT DATABASE()')" = "$DB_NAME" ] || fail "접속한 DB가 $DB_NAME 이 아닙니다"
[ "$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'")" = "1" ] \
  || fail "Flyway가 적용되지 않은 DB입니다. 서버를 이 DB로 먼저 띄우세요"
[ "$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'shop'")" = "1" ] \
  || fail "shop 테이블이 없습니다 (V1__init.sql 적용 전)"
[ "$(q 'SELECT COUNT(*) FROM shop')" = "0" ] \
  || fail "이미 데이터가 있습니다. 시연 DB를 새로 만든 뒤 실행하세요 (README 순서)"

mysql -h "$HOST" -P "$PORT" -u "$USER_NAME" --default-character-set=utf8mb4 "$DB_NAME" < "$SEED_FILE"
echo "완료: $DB_NAME 에 시연 시드를 넣었습니다."
