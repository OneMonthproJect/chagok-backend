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

-- 1. maintenance_record (07 3.7, D-36·D-55)
--    inspect_result: 점검 결과. record_type 이 INSPECT 일 때만 값을 가진다 (CHECK 2개, 07 6.1 이름 규칙).
ALTER TABLE maintenance_record ADD COLUMN inspect_result VARCHAR(20) NULL;
ALTER TABLE maintenance_record ADD CONSTRAINT ck_maintenance_record_inspect_result
    CHECK (inspect_result IN ('GOOD', 'CAUTION'));
ALTER TABLE maintenance_record ADD CONSTRAINT ck_maintenance_record_inspect_result_record_type
    CHECK (inspect_result IS NULL OR record_type = 'INSPECT');

--    version: 동시 수정 확인 (JPA @Version)
ALTER TABLE maintenance_record ADD COLUMN version INT NOT NULL DEFAULT 0;

--    updated_at: 이미 행이 있는 DB 에서도 돌도록 3단계 (NULL 허용 추가 → 기존 행을 created_at 으로 채움 → NOT NULL).
--    DEFAULT 는 붙이지 않는다 (07 6.1 타임스탬프 규칙, 값은 서버 JPA Auditing).
ALTER TABLE maintenance_record ADD COLUMN updated_at DATETIME NULL;
UPDATE maintenance_record SET updated_at = created_at;
ALTER TABLE maintenance_record MODIFY COLUMN updated_at DATETIME NOT NULL;

--    UNIQUE(shop_id, id): maintenance_record_revision 복합 FK 의 부모 키 (07 5.1, ⓑ 구역이 참조)
ALTER TABLE maintenance_record ADD CONSTRAINT uk_maintenance_record_shop_id_id UNIQUE (shop_id, id);

-- 2. reservation (07 3.8, D-52·D-27)
--    ck_reservation_status 를 지우고 같은 이름으로 'IN_PROGRESS' · 'NO_SHOW' 를 더해 다시 만든다.
ALTER TABLE reservation DROP CHECK ck_reservation_status;
ALTER TABLE reservation ADD CONSTRAINT ck_reservation_status
    CHECK (status IN ('RESERVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED', 'NO_SHOW'));

-- 3. customer · vehicle: 동시 수정 확인용 version (07 4장, POL-124)
ALTER TABLE customer ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE vehicle ADD COLUMN version INT NOT NULL DEFAULT 0;



-- =====================================================================
-- ⓑ 이력 표 CREATE (이재성)
--    maintenance_record_revision · reservation_revision
-- =====================================================================

