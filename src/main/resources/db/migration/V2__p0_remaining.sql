-- =====================================================================
-- V2__p0_remaining.sql — P0 나머지 스키마 (chagok-backend#31)
-- 원본: chagok-docs docs/07_data_ERD_DB설계서.md (3.7, 4.1, 4.2, 5.1, 6장, 6.1 이름 규칙)
--
-- 구역 분담 (같은 파일을 두 사람이 나눠 작성, 서로의 구역은 수정하지 않는다)
--   ⓐ 기존 표 ALTER   — 이영주
--   ⓑ 이력 표 CREATE  — 이재성
-- =====================================================================


-- =====================================================================
-- ⓐ 기존 표 ALTER (이영주)
--    maintenance_record · reservation · customer · vehicle
-- =====================================================================



-- =====================================================================
-- ⓑ 이력 표 CREATE (이재성)
--    maintenance_record_revision · reservation_revision
-- =====================================================================
-- 이름은 07 6.1 규칙과 6.4 V2 이름표(chagok-docs#114). 모두 64자 이하라 단축 예외 없음.
-- 스냅샷 칼럼(prev_*·new_*의 id)에는 FK를 걸지 않는다: 이미 복합 FK가 걸린 원본 행에서 복사한 값이다 (07 4.1·4.2).
-- 수정자 changed_by_staff_id 는 토큰의 계정이라 단일 FK (07 5.1).

-- 10. maintenance_record_revision — 정비 기록 수정 이력 (07 4.1, D-55)
--     fk_maintenance_record_revision_maintenance_record 는 ⓐ의 maintenance_record UNIQUE(shop_id, id)가 먼저 있어야 한다
CREATE TABLE maintenance_record_revision (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id              BIGINT       NOT NULL,
    record_id            BIGINT       NOT NULL,
    revision_no          INT          NOT NULL,
    changed_by_staff_id  BIGINT       NOT NULL,
    reason               VARCHAR(200) NULL,
    prev_record_date     DATE         NOT NULL,
    prev_mileage         INT          NULL,
    prev_record_type     VARCHAR(20)  NOT NULL,
    prev_service_item_id BIGINT       NOT NULL,
    prev_inspect_result  VARCHAR(20)  NULL,
    prev_memo            VARCHAR(200) NULL,
    prev_staff_id        BIGINT       NOT NULL,
    created_at           DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_maintenance_record_revision_record_id_revision_no UNIQUE (record_id, revision_no),
    INDEX idx_maintenance_record_revision_shop_id_record_id (shop_id, record_id),
    INDEX idx_maintenance_record_revision_changed_by_staff_id (changed_by_staff_id),
    CONSTRAINT fk_maintenance_record_revision_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_maintenance_record_revision_maintenance_record FOREIGN KEY (shop_id, record_id) REFERENCES maintenance_record (shop_id, id),
    CONSTRAINT fk_maintenance_record_revision_staff FOREIGN KEY (changed_by_staff_id) REFERENCES staff (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 11. reservation_revision — 예약 정정·재배정 이력 (07 4.2, D-53)
--     번호 칼럼 없음: 순서는 created_at, 같으면 id (API 41)
--     kind 별 칼럼: STATUS·ASSIGNEE는 이전·이후 값이 항상 있어 CHECK로 막는다 (reservation.status·assignee_staff_id NOT NULL).
--     MEMO는 이전 메모가 NULL일 수 있어 서버가 검사한다
CREATE TABLE reservation_revision (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id             BIGINT       NOT NULL,
    reservation_id      BIGINT       NOT NULL,
    kind                VARCHAR(20)  NOT NULL,
    changed_by_staff_id BIGINT       NOT NULL,
    reason              VARCHAR(200) NULL,
    prev_status         VARCHAR(20)  NULL,
    new_status          VARCHAR(20)  NULL,
    prev_memo           VARCHAR(200) NULL,
    new_memo            VARCHAR(200) NULL,
    prev_assignee_id    BIGINT       NULL,
    new_assignee_id     BIGINT       NULL,
    created_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_reservation_revision_kind CHECK (kind IN ('STATUS', 'MEMO', 'ASSIGNEE')),
    CONSTRAINT ck_reservation_revision_kind_status
        CHECK (kind <> 'STATUS' OR (prev_status IS NOT NULL AND new_status IS NOT NULL)),
    CONSTRAINT ck_reservation_revision_kind_assignee
        CHECK (kind <> 'ASSIGNEE' OR (prev_assignee_id IS NOT NULL AND new_assignee_id IS NOT NULL)),
    -- 복합 FK 인덱스이자 API 41 이력 목록(시간순) 조회용
    INDEX idx_reservation_revision_shop_id_reservation_id_created_at (shop_id, reservation_id, created_at),
    INDEX idx_reservation_revision_changed_by_staff_id (changed_by_staff_id),
    CONSTRAINT fk_reservation_revision_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_reservation_revision_reservation FOREIGN KEY (shop_id, reservation_id) REFERENCES reservation (shop_id, id),
    CONSTRAINT fk_reservation_revision_staff FOREIGN KEY (changed_by_staff_id) REFERENCES staff (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

