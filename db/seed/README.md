# 시연 시드 (`db/seed/`)

시연·발표용 데이터다. **시연 전용 DB에만** 넣는다. 서버가 자동으로 넣지 않는다(이 폴더는 classpath 밖이라 Flyway·Spring이 읽지 않는다).

| 파일 | 내용 |
|---|---|
| `demo_seed.sql` | 시연 데이터. 07 7장 시드 구성대로 채움 (chagok-backend#9, V1 기준). 07이 바뀌면 DB 담당자가 같이 고친다. 적용 확인은 CI db-test의 `DemoSeedTest`(트랜잭션 롤백) |
| `run-demo-seed.sh` | 안전 검사 후 `demo_seed.sql`을 실행하는 스크립트 |

## 규칙

1. 시연 DB 이름은 💡 `chagok_demo`로 고정한다. 개발 DB(`chagok`)·운영 DB와 같은 DB를 쓰지 않는다.
2. `demo_seed.sql`을 `mysql`로 직접 실행하지 않는다. 반드시 `run-demo-seed.sh`로 실행한다. 스크립트가 다음을 확인하고 하나라도 어긋나면 **실행을 거부**한다.
   - 접속한 DB 이름이 `DEMO_DB_NAME`(기본 `chagok_demo`)과 같다
   - Flyway가 적용된 DB다(`flyway_schema_history` 테이블이 있다)
   - `shop` 테이블이 비어 있다(이미 데이터가 있는 DB에 덮어쓰지 않는다)
3. 시드 SQL은 `DELETE`·`TRUNCATE`·`DROP`을 쓰지 않는다. 다시 넣고 싶으면 시연 DB를 지우고 새로 만든다(아래 순서).
4. 날짜는 오늘 기준 상대값(`CURDATE() - INTERVAL …`)으로 쓴다. 기대값은 `chagok-docs/docs/07` 7장.
5. 시연 계정(사장·정비사·관리자)의 비밀번호는 `1234`이고 BCrypt 해시로 넣는다. 첫 로그인 비밀번호 변경을 강제하지 않는다(`password_change_required = false`). 이 계정은 시연 DB에만 있고, 운영 코드·마이그레이션에는 넣지 않는다.
6. 테이블·컬럼은 만들지 않는다. 구조는 Flyway 마이그레이션만 만든다.

## 시연 DB 준비 순서 (DB 담당자)

```bash
# 1) 시연 DB를 새로 만든다 (기존 시연 DB가 있으면 지우고) — DB 담당자 권한
mysql -u root -p -e "DROP DATABASE IF EXISTS chagok_demo; CREATE DATABASE chagok_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 2) 서버를 시연 DB로 한 번 띄워 Flyway 마이그레이션을 적용한다 (.env의 DB_URL을 .../chagok_demo로)
./gradlew bootRun     # 뜬 것을 확인하고 끈다

# 3) 시드를 넣는다 (Git Bash)
DEMO_DB_USER=chagok bash db/seed/run-demo-seed.sh
```

시연 전날 위 1~3을 다시 하면 그날 기준 날짜로 다시 만들어진다.
