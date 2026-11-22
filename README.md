# 차곡 · chagok-backend

차곡(정비소용 예약 CRM)의 Spring Boot API 서버입니다. 기획 문서·협업 규칙·코드 구조 규칙은 **[chagok-docs](../chagok-docs)** 에 있습니다.

| 무엇 | 어디 |
|---|---|
| 범위·기능 ID·ERD·API 명세 | `chagok-docs/docs/00·04·07·08` |
| 코드 구조 규칙 (백엔드 1~6·8·9장) | `chagok-docs/CONVENTIONS.md` |
| 브랜치·이슈·PR·릴리스 | `chagok-docs/CONTRIBUTING.md` |
| 처음 보는 팀원 안내 | `chagok-docs/docs/etc/GUIDE_레포구조_협업규칙_AI스킬안내.md` |

## 지금 상태

초기 구성(B-0)이 끝난 상태입니다. 서버가 뜨고 DB에 연결되는 것까지 준비했고, **테이블은 아직 없습니다.** 실제 스키마(`V1__init.sql`)와 시연 데이터는 ERD(07) 확정 뒤 DB 담당자가 만듭니다.

| 항목 | 내용 |
|---|---|
| 기술 | Java 21, Spring Boot 4.1.2, Spring Data JPA, Spring Security, MySQL 8, Flyway, Gradle 9.8.2 (Wrapper 포함) |
| 패키지 | `com.wanted.chagok` — `global` / `domain` / `screen` (CONVENTIONS 2장). 지금은 각 패키지 역할만 `package-info.java`로 적어 둠 |
| 보안 | `SecurityConfig`: `/actuator/health`만 열고 나머지는 모두 닫힘. JWT·API 규칙은 인증 `기반` 이슈(B-1) |
| DB | `ddl-auto: validate`, `spring.sql.init.mode: never`(schema.sql·data.sql 자동 실행 안 함), 테이블은 Flyway만 만듦 |
| 마이그레이션 | `src/main/resources/db/migration/` (비어 있음) |
| 시연 데이터 | `db/seed/` (classpath 밖, 서버가 자동으로 넣지 않음) — [`db/seed/README.md`](db/seed/README.md) |
| 구조 검사 | `src/test/java/…/architecture/ArchitectureTest.java` (ArchUnit). 검사 대상이 아직 없는 규칙은 SKIPPED |
| 확인용 | `GET /actuator/health` → `{"status":"UP"}`면 DB 연결까지 성공 |

```text
chagok-backend/
├── gradlew · gradlew.bat · gradle/wrapper/      Gradle Wrapper (커밋 대상)
├── build.gradle · settings.gradle
├── db/seed/                                     시연 시드 (시연 전용 DB에만, 실행기로만)
├── src/main/java/com/wanted/chagok/
│   ├── ChagokApplication.java
│   ├── global/       config(SecurityConfig) · auth · error · response · web
│   ├── domain/       기능 이슈에서 도메인 패키지를 추가
│   └── screen/       target · visit · booking (기능 이슈에서)
├── src/main/resources/
│   ├── application.yaml
│   └── db/migration/         V{번호}__{설명}.sql — DB 담당자
└── src/test/java/com/wanted/chagok/
    ├── ChagokApplicationTests.java   @Tag("db") 앱 기동·Flyway·validate
    └── architecture/ArchitectureTest.java
```

## 실행

준비: JDK 21, DB 담당자가 만든 MySQL 8 DB와 계정. Gradle은 따로 설치하지 않아도 됩니다(Wrapper가 받음).

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
| `./gradlew dbTest` | DB 테스트(`@Tag("db")`): 앱 기동·Flyway·JPA validate, 이후 통합·격리 테스트 | ○ |

테스트 리포트: `build/reports/tests/test/index.html`, `build/reports/tests/dbTest/index.html`. SKIPPED(건너뜀)는 "검사 대상이 아직 없어 검증되지 않음"이지 통과가 아닙니다.

## CI (`.github/workflows/backend-ci.yml`)

| 작업 | 하는 일 |
|---|---|
| wrapper | Wrapper 파일이 있는지(없으면 실패), 공식 jar인지(`gradle/actions/wrapper-validation`) |
| build | `./gradlew build` — 컴파일·DB 없는 테스트. 결과 요약(통과·실패·건너뜀)과 리포트 보관 |
| db-test | MySQL 8 서비스 + `./gradlew dbTest` — 빈 DB에 Flyway 적용·앱 기동. 결과 요약과 리포트 보관 |

pr-check는 여기에 더해 **비밀값**(`.github/scripts/secret-scan.sh`)과 **DB·Flyway 규칙**(`.github/scripts/db-rules.sh`)을 검사합니다. 실패(❌)한 PR은 머지하지 않습니다(GitHub Free 운영이라 자동으로 막히지 않음, CONTRIBUTING 5장).

## 환경변수

`.env`(Git 제외) 또는 OS 환경변수로 넣습니다. OS 환경변수가 우선합니다. 기본값이 없어서 빠지면 서버가 뜨지 않습니다.

| 이름 | 예 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/chagok?characterEncoding=UTF-8&serverTimezone=Asia/Seoul` | |
| `DB_USERNAME` | | DB 담당자에게 받음 |
| `DB_PASSWORD` | | DB 담당자에게 받음 |

공개 레포이므로 실제 값은 어디에도 커밋하지 않습니다. JWT 서명 키(`JWT_SECRET` 💡)는 인증 이슈에서 추가합니다.

## DB·Flyway 규칙

1. DB는 백엔드에서만 연결한다. 프론트는 REST API(08)로만 데이터를 쓴다.
2. 테이블은 Flyway 마이그레이션으로만 만든다. JPA는 `validate`만, `schema.sql`·`data.sql` 자동 실행은 쓰지 않는다.
3. 최초 마이그레이션(`V1__init.sql`)은 ERD(07) 확정 뒤 DB 담당자가 작성한다.
4. **`develop`에 들어간 마이그레이션 파일은 고치지 않는다.** 바꿀 게 있으면 새 버전 파일을 더한다(pr-check가 막음). 아직 머지 안 된 내 PR의 파일은 고쳐도 된다.
5. 파일 이름: `V{번호}__{설명}.sql` (예: `V1__init.sql`, `V2__add_inspect_result.sql`). 번호는 머지 순서대로, 겹치면 나중에 머지하는 쪽이 올린다.
6. 실제 DB 생성·변경(DB·계정 만들기, 운영 DB 반영)은 DB 담당자가 한다.
7. 확정되지 않은 P1·P2 기능의 테이블·컬럼은 미리 만들지 않는다(00, 07 4장).
8. 마이그레이션을 바꾸는 PR은 `chagok-docs`의 07 문서 PR 링크가 필요하다(CONTRIBUTING 7장).
9. 시연 데이터는 `db/seed/demo_seed.sql`에만 두고, 시연 전용 DB(`chagok_demo` 💡)에 `db/seed/run-demo-seed.sh`로만 넣는다. 서버 실행 때 자동으로 들어가지 않는다. 개발·운영 DB의 데이터를 지우지 않는다.
10. 1~5·9번 중 자동으로 찾을 수 있는 것은 pr-check(`db-rules.sh`)가 ❌로 표시한다.

## 아직 넣지 않은 것

| 무엇 | 언제 |
|---|---|
| JWT 라이브러리·인증 필터, ErrorCode(08 0.3)·공통 응답(`details` 포함), Clock, PageLimit, `EndpointGuardTest` | 백엔드 `기반` 이슈 (12 백로그 B-1, 테스트 설계는 12 9장·TEST 명세 E1~E5) |
| `V1__init.sql`(`reservation.version` 포함), `db/seed/demo_seed.sql`(시연 계정 1234) | ERD 확정 뒤 DB 담당자 (B-3) |
| 도메인·screen 패키지 | 각 기능 이슈 |

JWT 라이브러리는 Spring Boot 4가 Jackson 3을 쓰기 때문에, 고를 때 Jackson 3·Spring Security 7과 맞는지 확인한다(예: Spring Security의 `oauth2-resource-server`(Nimbus) 또는 JJWT).

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
