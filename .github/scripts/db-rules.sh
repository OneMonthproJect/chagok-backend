#!/usr/bin/env bash
# DB·Flyway 규칙 검사 (chagok-backend). 어기면 실패한다.
# 사용: bash .github/scripts/db-rules.sh <base-sha> <head-sha>
# 규칙 원본: chagok-docs/CONVENTIONS.md 1장 7번·6장 「DB (Flyway)」, 이 레포 README 「DB·Flyway 규칙」
set -uo pipefail
BASE="$1"; HEAD="$2"; fail=0
err() { echo "::error file=$1::$2"; fail=1; }

changes=$(git diff --name-status "$BASE...$HEAD")
MIG="src/main/resources/db/migration/"

while IFS=$'\t' read -r status path rest; do
  [ -z "${status:-}" ] && continue
  # 이름이 바뀐 경우(R100 old new)는 새 경로 기준으로도 본다
  target="$path"; [[ "$status" == R* ]] && target="$rest"

  # 1) 이미 공유된 마이그레이션은 고치거나 지우거나 옮기지 않는다
  if [[ "$path" == "$MIG"* && "$path" != *.gitkeep && "$status" != A ]]; then
    err "$path" "이미 있는 마이그레이션 파일을 고치거나 지웠습니다($status). 새 버전 파일(V번호__설명.sql)을 더하세요."
  fi
  # 2) 마이그레이션 파일 이름
  if [[ "$target" == "$MIG"* && "$target" != *.gitkeep && "$status" != D ]]; then
    name="${target#$MIG}"
    [[ "$name" =~ ^V[0-9]+__[a-z0-9_]+\.sql$ ]] || err "$target" "마이그레이션 이름은 V{번호}__{소문자_설명}.sql 이어야 합니다 (반복 마이그레이션 R__·하위 폴더 금지)."
  fi
  [[ "$status" == D ]] && continue
  # 3) schema.sql / data.sql 자동 실행 금지
  case "$(basename "$target")" in
    schema.sql|data.sql|schema-*.sql|data-*.sql) err "$target" "schema.sql·data.sql 자동 실행은 쓰지 않습니다. 구조는 Flyway, 시연 데이터는 db/seed/." ;;
  esac
  # 4) classpath 안의 SQL은 마이그레이션만 (시드가 서버 실행 때 들어가지 않게)
  if [[ "$target" == src/main/resources/*.sql && "$target" != "$MIG"* ]]; then
    err "$target" "src/main/resources 안의 SQL은 db/migration만 둡니다. 시연 데이터는 레포 루트 db/seed/."
  fi
done <<< "$changes"

# 5) JPA는 validate만, SQL 자동 초기화 금지 (현재 파일 전체 기준)
for f in $(git ls-files 'src/main/resources/application*.yml' 'src/main/resources/application*.yaml' 'src/main/resources/application*.properties'); do
  if grep -Eq 'ddl-auto' "$f" && grep -E 'ddl-auto' "$f" | grep -Evq 'ddl-auto\s*[:=]\s*validate\s*(#.*)?$'; then
    err "$f" "spring.jpa.hibernate.ddl-auto는 validate만 씁니다."
  fi
  if grep -Eq '^\s*mode\s*:\s*(always|embedded)\b|sql\.init\.mode\s*=\s*(always|embedded)' "$f"; then
    err "$f" "spring.sql.init.mode는 never여야 합니다 (schema.sql·data.sql 자동 실행 금지)."
  fi
done

[ $fail -eq 0 ] && echo "DB·Flyway 규칙 통과"
exit $fail
