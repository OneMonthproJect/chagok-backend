-- =====================================================================
-- V3__p1_staff_active_contact_log_terms.sql — P1 스키마 (chagok-backend#134)
-- 원본: chagok-docs docs/07_data_ERD_DB설계서.md (3.3, 4.3, 4.4, 5.1, 6장, 6.1 이름 규칙)
--
-- 이 파일에 넣는 것
--   1. staff.active                 — 정비사 비활성화 (F-STF-01, D-57, 07 3.3)
--   2. contact_log                  — 연락 기록 (F-CON-02, D-59, 07 4.3)
--   3. terms_agreement              — 서비스 이용약관 동의 기록 (F-AUTH-04, D-61, 07 4.4)
--
-- 이 파일에 넣지 않는 것
--   - customer.status · customer.anonymized_at · vehicle.active (D-56)
--     05 POL-46(법적 근거 검토, 담당 PM)이 끝날 때까지 만들지 않는다 (12 6장). 끝나면 새 버전 파일(V4 이후)로 더한다.
--   - 시연 시드 행 (terms_agreement 포함). 시드는 db/seed/demo_seed.sql 에만 둔다.
--
-- 규칙
--   - 기존 표 변경(ALTER)을 먼저, 새 표 생성(CREATE)을 나중에 쓴다. 부모가 먼저 있어야 FK를 만들 수 있다.
--   - created_at 은 DATETIME NOT NULL, DEFAULT 없음 (값은 서버 JPA Auditing + Clock, 07 6.1).
--   - 제약 이름: uk_{테이블}_{칼럼} · fk_{자식}_{부모} · ck_{테이블}_{칼럼} · idx_{테이블}_{칼럼}. 모두 64자 이하라 단축 예외 없음.
--   - 삭제·수정 동작은 모두 기본값(RESTRICT).
--   - 이 파일은 develop 머지 후 고치지 않는다. 바꿀 것은 새 버전 파일로.
-- =====================================================================


-- =====================================================================
-- 기존 표 ALTER
-- =====================================================================

-- 1. staff.active — 정비사 비활성화 (07 3.3, D-57). 삭제는 없다.
--    기존 행은 모두 활성(TRUE). 값을 쓰지 않는 기존 INSERT 코드도 깨지지 않는다.
ALTER TABLE staff ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;


-- =====================================================================
-- 새 표 CREATE
-- =====================================================================

-- 2. contact_log — 연락 기록 (07 4.3, D-59)
--    수정·삭제하지 않는다 (updated_at 없음). "연락함" 꼬리표는 contacted_at 이 30일 이내인 가장 최근 기록으로 계산한다(저장하지 않음).
--    customer_id 는 요청 값(경로)이라 복합 FK (shop_id, customer_id) → customer(shop_id, id) 로 다른 정비소 고객을 막는다 (07 5.1).
--    staff_id 는 토큰의 계정이라 단일 FK.
CREATE TABLE contact_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id      BIGINT       NOT NULL,
    customer_id  BIGINT       NOT NULL,
    result       VARCHAR(20)  NOT NULL,
    memo         VARCHAR(200) NULL,
    contacted_at DATETIME     NOT NULL,
    staff_id     BIGINT       NOT NULL,
    created_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_contact_log_result
        CHECK (result IN ('RESERVED', 'ABSENT', 'ALREADY_DONE', 'CONTACTED', 'WRONG_NUMBER', 'REFUSED')),
    -- 복합 FK 인덱스이자 "연락함(날짜)" 꼬리표 조회용
    INDEX idx_contact_log_shop_id_customer_id_contacted_at (shop_id, customer_id, contacted_at),
    INDEX idx_contact_log_staff_id (staff_id),
    CONSTRAINT fk_contact_log_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_contact_log_customer FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id),
    CONSTRAINT fk_contact_log_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. terms_agreement — 서비스 이용약관 동의 기록 (07 4.4, D-61)
--    사장이 정비소를 대표해 동의한 기록. 최소 기록만 둔다 (전자서명·다중 동의·약관 본문 저장 없음).
--    UNIQUE(shop_id, terms_version): 같은 정비소·같은 버전은 1건. shop_id FK 인덱스를 겸한다.
--    staff_id 는 토큰의 계정이라 단일 FK (07 5.1 원칙, created_by_staff_id·changed_by_staff_id 와 같음).
--    수정·삭제하지 않는다 (updated_at 없음). 정비사 행은 없다.
CREATE TABLE terms_agreement (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id       BIGINT      NOT NULL,
    staff_id      BIGINT      NOT NULL,
    terms_version VARCHAR(20) NOT NULL,
    accepted_at   DATETIME    NOT NULL,
    created_at    DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_terms_agreement_shop_id_terms_version UNIQUE (shop_id, terms_version),
    INDEX idx_terms_agreement_staff_id (staff_id),
    CONSTRAINT fk_terms_agreement_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_terms_agreement_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
