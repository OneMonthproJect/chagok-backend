# CLAUDE.md — chagok-backend에서 AI로 작업할 때

## 먼저 확인

- `.claude/rules/`·`.claude/skills/`·`.claude/agents/`는 `chagok-docs`에서 복사한 사본이다(커밋되지 않음). **비어 있으면** 작업을 시작하지 말고 사용자에게 `bash ../chagok-docs/scripts/sync-claude.sh` 실행을 요청한다.
- 공용 규칙: `.claude/rules/ai-rules.md`, `.claude/rules/source-of-truth.md`
- 원본 문서는 옆 레포에 있다(`.claude/settings.json`의 `additionalDirectories`로 읽을 수 있음):

| 무엇 | 원본 |
|---|---|
| 코드 구조 (백엔드는 1~6장, 8·9장) | `../chagok-docs/CONVENTIONS.md` |
| 브랜치·이슈·커밋·PR·릴리스·문서 동기화 | `../chagok-docs/CONTRIBUTING.md` |
| 범위·기능 ID·ERD·API | `../chagok-docs/docs/00`, `04`, `07`, `08` |

## 이 레포에서만

- 패키지 루트 `com.wanted.chagok`, 세 층 `global` / `domain` / `screen` (CONVENTIONS 2장).
- **DB 구조는 Flyway 버전 파일이 기준**이다: `src/main/resources/db/migration/V{번호}__{설명}.sql`. `develop`에 머지된 버전 파일은 고치지 않고 새 번호를 더한다. Entity는 마이그레이션에 맞추고 `ddl-auto: validate`.
- 시연 시드는 레포 루트 `db/seed/demo_seed.sql`에만 둔다(classpath 밖, `chagok-seed-data` 스킬). 시연 전용 DB에 `db/seed/run-demo-seed.sh`로만 넣는다. DB를 직접 실행·변경하지 않는다.
- 명세가 바뀌는 변경(마이그레이션, Controller·DTO, ErrorCode)은 `chagok-docs`에 문서 PR이 먼저 또는 함께 있어야 한다. 코드 PR 본문 「문서 PR」 칸에 `OneMonthproJect/chagok-docs#번호`를 적는다. 문서는 이 레포에서 고치지 않는다.
- 절차·검증 범위는 `ai-rules.md` 「작업 등급」(L0~L3). 이 레포의 L3 대상: `db/migration`·`db/seed`, `global/auth`·`SecurityConfig`, `getOwned`·Repository의 `shopId` 조건, ErrorCode, `.github/`.
- 점검 명령: `./gradlew build`(DB 없는 테스트, ArchitectureTest 포함), `./gradlew dbTest`(빈 테스트 전용 DB에서 — README 「`dbTest`는 테스트 전용 DB로」).
- 테스트를 통과시키려고 ArchitectureTest·SecurityConfig·검증을 약하게 바꾸지 않는다(CONVENTIONS 12장).
- 프론트 호출을 확인해야 하면 `../chagok-frontend`를 읽기만 한다.
