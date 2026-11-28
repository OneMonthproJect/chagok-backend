# 차곡 · chagok-backend

차곡(정비소용 예약 CRM)의 Spring Boot API 서버입니다. 기획 문서·협업 규칙·코드 구조 규칙은 **[chagok-docs](../chagok-docs)** 에 있습니다.

| 무엇 | 어디 |
|---|---|
| 범위·기능 ID·ERD·API 명세 | `chagok-docs/docs/00·04·07·08` |
| 코드 구조 규칙 (백엔드 1~6·8·9장) | `chagok-docs/CONVENTIONS.md` |
| 브랜치·이슈·PR·릴리스 | `chagok-docs/CONTRIBUTING.md` |
| 처음 보는 팀원 안내 | `chagok-docs/docs/etc/GUIDE_레포구조_협업규칙_AI스킬안내.md` |

## 지금 상태

P0-핵심과 P0-나머지가 들어간 상태입니다(2026-11-27, develop `753cd3c`). M2 통합 검증(태그 `v0.5-m2-integration`, 2026-11-26)을 통과했고, P0-나머지 구현 이슈(12 백로그 5장)는 모두 닫혔습니다. 08의 P0 API(01~23, 36~42)가 있고, P1 API(24~35, 43~52)는 아직 없습니다. Flyway는 `V1__init.sql`(P0-핵심)·`V2__p0_remaining.sql`(P0-나머지)까지, 시연 시드는 V2 기준입니다. 다음 마일스톤 순서는 `chagok-docs` README 「지금 단계」를 봅니다.

| 항목 | 내용 |
|---|---|
| 기술 | Java 21, Spring Boot 4.1.2, Spring Data JPA, Spring Security, MySQL 8, Flyway, Gradle 9.8.2 (Wrapper 포함) |
| 패키지 | `com.wanted.chagok` — `global` / `domain` / `screen` (CONVENTIONS 2장). `global`은 공통 기반 구현(아래 「공통 기반 사용법」), `domain`은 account·shop·customer·item·maintenance·reservation·cycle, `screen`은 target·visit·booking |
| 인증·인가 | 로그인 01(`POST /api/auth/login`, 사장·정비사)·02(`POST /api/admin/auth/login`, 서비스 관리자) → `domain/account`의 `AuthService`가 확인하고 `global/auth/JwtIssuer`가 JWT를 발급(HS256, 12시간, 서버에 저장하지 않음). 검증은 Spring Security OAuth2 Resource Server + Nimbus. `GET /actuator/health`·로그인 POST만 공개, `/api/admin/**` ADMIN, `/api/**` OWNER·MECHANIC, 그 밖 모두 거부. 401·403도 공통 응답 — 아래 「공통 기반 사용법」 |
| DB | MySQL 8, `ddl-auto: validate`, `open-in-view: false`, `jdbc.time_zone: Asia/Seoul`, `spring.sql.init.mode: never`(schema.sql·data.sql 자동 실행 안 함), 테이블은 Flyway만 만듦 |
| 마이그레이션 | `src/main/resources/db/migration/V1__init.sql` — 9개 테이블, 복합 FK 8개(`shop_id` 격리)·단일 FK 9개, UNIQUE·CHECK·인덱스 (07 3장·6.1). `V2__p0_remaining.sql` — P0-나머지 칼럼·CHECK와 이력 표 2개(07 4장·6장·6.4, chagok-backend#31). 둘 다 `develop`에 머지됐으므로 고치지 않는다. 다음은 `V3`(P1, 07 6장) |
| 시연 데이터 | `db/seed/demo_seed.sql` (07 7장, classpath 밖, 서버가 자동으로 넣지 않음) — [`db/seed/README.md`](db/seed/README.md) |
| 구조 검사 | `src/test/java/…/architecture/ArchitectureTest.java` (ArchUnit). 검사 대상이 없는 규칙은 SKIPPED로 남는다(2026-11-27 develop CI에서는 20개 모두 실행·통과, SKIPPED 0 — `chagok-docs` TEST 2장) |
| 확인용 | `GET /actuator/health` → `{"status":"UP"}`면 DB 연결까지 성공 |

```text
chagok-backend/
├── gradlew · gradlew.bat · gradle/wrapper/      Gradle Wrapper (커밋 대상)
├── build.gradle · settings.gradle
├── db/seed/                                     시연 시드 (시연 전용 DB에만, 실행기로만)
├── src/main/java/com/wanted/chagok/
│   ├── ChagokApplication.java
│   ├── global/       auth(JWT·LoginStaff·@CurrentStaff) · config(Security·Clock·Auditing·Jackson) · error · response · web
│   ├── domain/       account · shop · customer · item · maintenance · reservation · cycle
│   └── screen/       target · visit · booking
├── src/main/resources/
│   ├── application.yaml
│   └── db/migration/         V{번호}__{설명}.sql — DB 담당자
└── src/test/java/com/wanted/chagok/
    ├── ChagokApplicationTests.java              @Tag("db") 앱 기동·Flyway·validate
    ├── architecture/ArchitectureTest.java
    ├── domain/ · screen/ · global/              기능·격리(X)·인증(EndpointGuardTest) 테스트, 이름에 04·08의 T·R·H·X ID
    ├── migration/V1InitMigrationTest.java       @Tag("db") 빈 DB에 V1→V2, 07 6.3 V1-1~V1-10·V1-12·V1-13 + 6.4 V2 검사
    └── seed/DemoSeedFileTest.java · DemoSeedTest.java   시드 파일 검사(DB 없음) · @Tag("db") V1-11
```

## 실행

준비: JDK 21, 내 PC의 MySQL 8과 그 안에 직접 만든 DB·계정(아래 「로컬 DB 준비」). Gradle은 따로 설치하지 않아도 됩니다(Wrapper가 받음).

```bash
cp .env.example .env          # Windows PowerShell: copy .env.example .env  → 값 채우기
./gradlew bootRun             # Windows: gradlew.bat bootRun
# 다른 창에서
curl http://localhost:8080/actuator/health    # {"status":"UP"}
```

| 명령 | 하는 일 | DB 필요 |
|---|---|---|
| `./gradlew bootRun` | 서버 실행 (8080). 시작할 때 Flyway가 `db/migration`을 적용 | ○ |
| `./gradlew build` | 컴파일 + DB 없는 테스트(ArchitectureTest·단위 테스트) | × |
| `./gradlew dbTest` | DB 테스트(`@Tag("db")`): 앱 기동·Flyway·JPA validate, V1 제약, 시드 적용, 이후 통합·격리 테스트. **빈 테스트 전용 DB에서** (아래) | ○ |
| `./gradlew tzTest` | `JvmTimeZoneDbTest`만 UTC로 시작한 별도 JVM에서 실행(UTC 서버에서도 앱이 Asia/Seoul로 고정돼 SQL로 넣은 시각을 같은 값으로 읽는지, backend#65). `dbTest`가 끝나면 자동으로 이어서 돈다(backend#122) | ○ |

테스트 리포트: `build/reports/tests/test/index.html`, `build/reports/tests/dbTest/index.html`. SKIPPED(건너뜀)는 "검사 대상이 아직 없어 검증되지 않음"이지 통과가 아닙니다.

### 로컬 DB 준비 (내 PC의 MySQL 8에 직접)

DB는 용도별로 나눠 씁니다. 같은 DB를 두 용도로 쓰지 않습니다. 개발·테스트·시연 DB는 모두 각자 PC의 MySQL에 만들고, 팀이 함께 쓰는 DB는 없습니다. CI는 실행마다 새로 만드는 일회용 MySQL 컨테이너를 씁니다(아래 「CI」).

| 용도 | DB 이름 | 들어가는 것 |
|---|---|---|
| 개발 (`bootRun`) | `chagok` | Flyway가 만든 테이블 + 내가 화면·API로 넣은 데이터 |
| 테스트 (`dbTest`) | 예: `chagok_test` | 비어 있어야 함. 테스트는 트랜잭션을 롤백해 행을 남기지 않는다 |
| 시연 | `chagok_demo` | 시연 시드만 (`db/seed/run-demo-seed.sh`) |

```sql
-- 내 PC MySQL에서 (root 등 DB를 만들 수 있는 계정으로). 계정 이름·비밀번호는 각자 정한다
CREATE DATABASE chagok      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE chagok_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'chagok'@'localhost' IDENTIFIED BY '내 비밀번호';
GRANT ALL ON chagok.*      TO 'chagok'@'localhost';
GRANT ALL ON chagok_test.* TO 'chagok'@'localhost';
```

테이블은 만들지 않습니다. 서버(`bootRun`)나 `dbTest`가 시작할 때 Flyway가 `V1__init.sql`부터 차례로 적용합니다. 이미 적용된 DB에서는 checksum만 확인하고 넘어갑니다(`Schema … is up to date`).

### `dbTest`는 테스트 전용 DB로

`dbTest`도 `.env`의 `DB_URL`을 읽습니다. 시드 테스트(`DemoSeedTest`)는 `shop`이 빈 DB를 전제로 하고, Flyway는 연결한 DB에 마이그레이션을 적용하므로 **개발 DB(`chagok`)로 돌리지 않습니다.** OS 환경변수가 `.env`보다 우선하므로 실행할 때만 테스트 DB로 바꿉니다. 앱을 띄우는 테스트라 `JWT_SECRET`도 필요합니다(`.env`에 있으면 그대로 쓰임, CI는 실행마다 임의 키를 만듦).

```bash
# Git Bash
DB_URL='jdbc:mysql://localhost:3306/chagok_test?characterEncoding=UTF-8&serverTimezone=Asia/Seoul' ./gradlew dbTest
```

```powershell
# PowerShell
$env:DB_URL = 'jdbc:mysql://localhost:3306/chagok_test?characterEncoding=UTF-8&serverTimezone=Asia/Seoul'; .\gradlew.bat dbTest; Remove-Item Env:DB_URL
```

같은 결과로 다시 돌리려면 `./gradlew dbTest --rerun` (환경변수만 바꾸면 Gradle이 "최신"으로 보고 건너뛸 수 있음). 테스트가 `shop`이 비어 있지 않다고 실패하면, 그 DB에 다른 데이터가 들어간 것입니다. 테스트 DB를 새로 만듭니다.

`flyway repair`나 `flyway_schema_history` 직접 수정으로 checksum 오류를 숨기지 않습니다. checksum 오류는 머지된 마이그레이션 파일이 바뀌었다는 뜻이므로 원인을 DB 담당자와 확인합니다.

## CI (`.github/workflows/backend-ci.yml`)

| 작업 | 하는 일 |
|---|---|
| wrapper | Wrapper 파일이 있는지(없으면 실패), 공식 jar인지(`gradle/actions/wrapper-validation`) |
| build | `./gradlew build` — 컴파일·DB 없는 테스트. 결과 요약(통과·실패·건너뜀)과 리포트 보관 |
| db-test | 일회용 MySQL 8 서비스 컨테이너(실행마다 새로 만들고 끝나면 사라짐, CI 전용 값) + `./gradlew dbTest`(이어서 `tzTest`) — 빈 DB에 Flyway 적용·앱 기동. 결과 요약과 리포트 보관 |

pr-check는 여기에 더해 **비밀값**(`.github/scripts/secret-scan.sh`)과 **DB·Flyway 규칙**(`.github/scripts/db-rules.sh`)을 검사합니다. 실패(❌)한 PR은 머지하지 않습니다(GitHub Free 운영이라 자동으로 막히지 않음, CONTRIBUTING 5장).

## 환경변수

`.env`(Git 제외) 또는 OS 환경변수로 넣습니다. OS 환경변수가 우선합니다. 기본값이 없어서 빠지면 서버가 뜨지 않습니다.

| 이름 | 예 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/chagok?characterEncoding=UTF-8&serverTimezone=Asia/Seoul` | 개발 DB. `dbTest`는 실행할 때 테스트 DB로 바꿈(위) |
| `DB_USERNAME` | `chagok` | 각자 로컬 MySQL에 생성한 DB 계정 이름(위 「로컬 DB 준비」의 `CREATE USER`) |
| `DB_PASSWORD` | | 그 계정의 비밀번호. 각자 정하고 공유·커밋하지 않는다 |
| `JWT_SECRET` | `openssl rand -base64 48`로 만든 값 | JWT 서명 키(HS256). **32바이트 이상**, 없거나 짧으면 서버가 뜨지 않는다. 각자 로컬에서 만들고 공유·커밋하지 않는다. CI db-test는 실행마다 새로 만든다 |

공개 레포이므로 실제 값은 어디에도 커밋하지 않습니다. `dbTest`도 앱을 띄우므로 `JWT_SECRET`이 필요합니다.

## 공통 기반 사용법 (global)

| 필요한 것 | 쓰는 법 |
|---|---|
| 로그인한 계정 | Controller 파라미터 `@CurrentStaff LoginStaff staff` → `staff.shopId()`로만 정비소 데이터 조회(요청의 shopId는 받지 않음, 08 0.1). 서비스 관리자는 `LoginAdmin`(shopId 없음) |
| 성공 응답 | `return ApiResponse.ok(data);` — `{ success, data, error: null }` (08 0.2) |
| 업무 오류 | `throw new BusinessException(ErrorCode.NOT_FOUND)`, 화면 안내 값이 있으면 `new BusinessException(code, Map.of("reservationId", id))`, 입력 하나가 틀렸으면 `BusinessException.invalidField("reservedAt", "30분 단위로 입력해 주세요.")`. Controller에서 try/catch 하지 않는다 |
| 표준 예외 | `@Valid` 실패·잘못된 JSON·타입 불일치·필수 파라미터 누락·405·406·415 → 400 `INVALID_INPUT`, 없는 경로 → 404, `@Version` 충돌 → 409 `CONCURRENT_MODIFICATION`, `AccessDeniedException` → 403 `FORBIDDEN`, `ResponseStatusException` → 401·403·404는 그 코드·그 밖 4xx는 `INVALID_INPUT`·5xx는 `INTERNAL_ERROR`, 그 밖 → 500(내부 정보는 응답에 없음, 서버 로그에 요청 ID와 함께). DB 제약 위반을 `CONFLICT`로 바꾸지 않는다(08 0.3 M1 결정 A, 406·`ResponseStatusException`은 chagok-docs#186) |
| ErrorCode | 08 0.3 P0-핵심 11개 + P0-나머지 3개(#45). P0-나머지 기능 이슈는 이 파일을 고치지 않는다. P1 코드는 그 단계 이슈에서 08과 같은 PR로 |
| 지금 시각 | `Clock` 빈 주입 → `LocalDate.now(clock)`. `now()` 직접 호출 금지. 테스트는 `Clock.fixed(…)` |
| 생성·수정 시각 | Entity에 `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate`(updatable = false)·`@LastModifiedDate` (07 6.2). 값은 Clock 기준 |
| 일시 JSON | `LocalDateTime` → `"2026-11-24T10:00:00"`(시간대·소수 초 없음), `LocalDate` → `"2026-11-24"` (08 0.1) |
| 쪽 나누기 | `PageLimit limit = PageLimit.of(page, size)` (생략 0·20, 50 초과 → 50, 음수·0 이하 400) → `PageRequest.of(limit.page(), limit.size(), sort)`는 도메인에서 |
| 새 API 경로 | `SecurityConfig`에 등록하지 않은 경로는 닫혀 있다. 공개 경로는 로그인 01·02의 POST와 `GET /actuator/health`뿐이다(로그인 경로는 요청의 `Authorization`을 읽지 않음, D-72) |
| 요청 로그 | 모든 로그 줄에 `[요청 ID]`. 요청 끝에 메서드·경로·상태·시간 한 줄(쿼리스트링·토큰은 남기지 않음) |

토큰 클레임 규약(`sub`·`role`·`shopId`·`exp`)은 `global/auth/TokenClaims.java` 주석. 운영 발급은 `global/auth/JwtIssuer`(로그인 01·02에서만 호출, 토큰 문자열은 로그·예외 메시지에 남기지 않음)다. 테스트에서 로그인 없이 토큰이 필요하면 테스트 전용 `src/test/…/global/auth/TestJwts`를 쓴다.

## DB·Flyway 규칙

1. DB는 백엔드에서만 연결한다. 프론트는 REST API(08)로만 데이터를 쓴다.
2. 테이블은 Flyway 마이그레이션으로만 만든다. JPA는 `validate`만, `schema.sql`·`data.sql` 자동 실행은 쓰지 않는다.
3. 최초 마이그레이션(`V1__init.sql`)은 ERD(07) 확정 뒤 DB 담당자가 작성한다.
4. **`develop`에 들어간 마이그레이션 파일은 고치지 않는다.** 바꿀 게 있으면 새 버전 파일을 더한다(pr-check가 막음). 아직 머지 안 된 내 PR의 파일은 고쳐도 된다.
5. 파일 이름: `V{번호}__{설명}.sql` (예: `V1__init.sql`, `V2__add_inspect_result.sql`). 번호는 머지 순서대로, 겹치면 나중에 머지하는 쪽이 올린다.
6. 개발·테스트·시연 DB와 계정은 각자 내 PC의 MySQL에 직접 만든다(「로컬 DB 준비」, 시연 DB는 `db/seed/README.md`). 팀이 함께 쓰는 DB는 없다. 스키마는 DB에서 직접 바꾸지 않고 마이그레이션 PR로만 바꾸며, `db/`·`src/main/resources/db/` 변경은 DB관리가 리뷰한다(CODEOWNERS). 운영 DB를 두게 되면 그 생성·반영은 DB관리가 한다.
7. 확정되지 않은 P1·P2 기능의 테이블·컬럼은 미리 만들지 않는다(00, 07 4장).
8. 마이그레이션을 바꾸는 PR은 `chagok-docs`의 07 문서 PR 링크가 필요하다(CONTRIBUTING 7장).
9. 시연 데이터는 `db/seed/demo_seed.sql`에만 두고, 시연 전용 DB(`chagok_demo` 💡)에 `db/seed/run-demo-seed.sh`로만 넣는다. 서버 실행 때 자동으로 들어가지 않는다. 개발·운영 DB의 데이터를 지우지 않는다.
10. 1~5·9번 중 자동으로 찾을 수 있는 것은 pr-check(`db-rules.sh`)가 ❌로 표시한다.

## 아직 넣지 않은 것

| 무엇 | 언제 |
|---|---|
| P1 API(24~35, 43~52)와 P1 패키지(10 3장의 `domain/contact`·`screen/stats` 등) | P1 이슈 (12 백로그 6장) |
| **이미 발급된 토큰의 계정 상태 재검증** (비활성 정비사·정지 정비소의 기존 토큰은 지금 만료(최대 12시간)까지 유효) | 정책 결정 chagok-docs#64 → P1 정비사 비활성화(34-3) 착수 전, 실제 운영·최종 보안 검증 전 |
| `V3`(P1) | P0-나머지 완료 뒤 (07 6장, D-56 칼럼은 POL-46 확인 후) |

JWT는 B-1에서 Spring Security `oauth2-resource-server`(Nimbus JOSE)로 정했다(Jackson에 의존하지 않아 Spring Boot 4의 Jackson 3과 충돌 없음).

## 작업 폴더와 AI 설정

이 레포는 `chagok-docs` 옆에 있어야 합니다.

```text
chagok/
├── chagok-docs/
├── chagok-backend/    ← 여기
└── chagok-frontend/
```

Claude를 쓴다면 처음 한 번, 그리고 `chagok-docs`를 pull한 뒤 공용 AI 설정을 복사합니다 (Git Bash).

```bash
bash ../chagok-docs/scripts/sync-claude.sh
```

`.claude/rules·skills·agents`는 복사본이라 커밋되지 않습니다. 고칠 일이 있으면 `chagok-docs`에서 고칩니다.

## 작업할 때마다

```bash
git switch develop && git pull
git switch -c feature/#이슈번호-short-name     # 버그는 fix/, 설정은 chore/
# 작업 → 커밋 → push → develop으로 PR
```

명세(07·08)가 바뀌는 변경이면 `chagok-docs`에 문서 PR을 먼저(또는 함께) 올리고, 코드 PR 본문 「문서 PR」 칸에 링크를 적습니다. 마이그레이션이 바뀌었는데 링크가 없으면 pr-check가 머지를 막습니다.
