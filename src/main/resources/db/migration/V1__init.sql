-- =====================================================================
-- V1__init.sql — P0-핵심 기준 스키마 (chagok-backend#4, 12 백로그 B-3)
-- 원본: chagok-docs docs/07_data_ERD_DB설계서.md (develop b0f30b6, docs PR #13)
--   1장 원칙 · 3장 테이블 · 5.1 복합 FK · 6.1 생성 순서·이름 규칙
--
-- - 9개 테이블. 생성 순서는 07 6.1 (부모가 먼저).
-- - 복합 FK 6개 (shop_id, 부모_id) → 부모(shop_id, id). 부모 쪽 UNIQUE(shop_id, id) 4개는
--   부모의 CREATE TABLE 안에 둔다 (customer · vehicle · service_item · reservation).
-- - 단일 FK 10개: shop_id 7개, staff 2개, platform_admin 1개. 삭제·수정 동작은 모두 기본값(RESTRICT).
-- - created_at · updated_at 은 DATETIME NOT NULL, DEFAULT · ON UPDATE 없음 (값은 서버 JPA Auditing + Clock).
-- - 제약 이름: uk_{테이블}_{칼럼} · fk_{자식}_{부모} · ck_{테이블}_{칼럼} · idx_{테이블}_{칼럼}.
--   예외 1건: maintenance_record 의 (shop_id, vehicle_id, service_item_id, record_date) 인덱스는
--   규칙대로면 69자라 MySQL 식별자 한도(64자)를 넘어 idx_maintenance_record_shop_vehicle_item_date 로 줄였다.
--   칼럼·순서는 07 3.7 그대로. 07 반영은 후속 문서 작업.
-- - V1에 넣지 않는 것 (07 4장): maintenance_record.inspect_result, reservation.status 'NO_SHOW',
--   customer.version · vehicle.version, contact_log. 시연 시드·기본 기준표 행도 넣지 않는다 (07 7장).
-- - 이 파일은 develop 머지 후 고치지 않는다. 바꿀 것은 새 버전 파일로.
-- =====================================================================

-- 1. platform_admin — 서비스 관리자 (07 3.1). shop_id 없음 (I-5)
CREATE TABLE platform_admin (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    login_id      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_platform_admin_login_id UNIQUE (login_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. shop — 정비소 (07 3.2)
CREATE TABLE shop (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    name                VARCHAR(50) NOT NULL,
    phone               VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_admin_id BIGINT      NULL,
    created_at          DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_shop_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    INDEX idx_shop_created_by_admin_id (created_by_admin_id),
    CONSTRAINT fk_shop_platform_admin FOREIGN KEY (created_by_admin_id) REFERENCES platform_admin (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. staff — 사장·정비사 계정 (07 3.3). login_id 는 전체에서 유일
CREATE TABLE staff (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id                  BIGINT       NOT NULL,
    login_id                 VARCHAR(50)  NOT NULL,
    password_hash            VARCHAR(100) NOT NULL,
    name                     VARCHAR(50)  NOT NULL,
    role                     VARCHAR(20)  NOT NULL,
    password_change_required BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_staff_login_id UNIQUE (login_id),
    CONSTRAINT ck_staff_role CHECK (role IN ('OWNER', 'MECHANIC')),
    INDEX idx_staff_shop_id (shop_id),
    CONSTRAINT fk_staff_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. customer — 고객 (07 3.4). 전화번호 중복 허용
CREATE TABLE customer (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id    BIGINT      NOT NULL,
    name       VARCHAR(50) NOT NULL,
    phone      VARCHAR(20) NOT NULL,
    consent    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_customer_shop_id_id UNIQUE (shop_id, id),
    INDEX idx_customer_shop_id_phone (shop_id, phone),
    INDEX idx_customer_shop_id_name (shop_id, name),
    CONSTRAINT fk_customer_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. service_item — 정비 항목·주기 (07 3.6). 기본 기준표 6행은 시드·서버가 넣는다
CREATE TABLE service_item (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id      BIGINT      NOT NULL,
    code         VARCHAR(30) NOT NULL,
    name         VARCHAR(30) NOT NULL,
    cycle_months SMALLINT    NOT NULL,
    cycle_km     INT         NOT NULL,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL,
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_service_item_shop_id_code UNIQUE (shop_id, code),
    CONSTRAINT uk_service_item_shop_id_id UNIQUE (shop_id, id),
    CONSTRAINT ck_service_item_cycle_months CHECK (cycle_months > 0),
    CONSTRAINT ck_service_item_cycle_km CHECK (cycle_km > 0),
    CONSTRAINT fk_service_item_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. vehicle — 차량 (07 3.5). 차량번호 중복 허용 (UNIQUE 없음, POL-32)
CREATE TABLE vehicle (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id        BIGINT      NOT NULL,
    customer_id    BIGINT      NOT NULL,
    plate_number   VARCHAR(20) NOT NULL,
    model          VARCHAR(50) NULL,
    model_year     SMALLINT    NULL,
    latest_mileage INT         NULL,
    created_at     DATETIME    NOT NULL,
    updated_at     DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_vehicle_shop_id_id UNIQUE (shop_id, id),
    CONSTRAINT ck_vehicle_latest_mileage CHECK (latest_mileage >= 0),
    INDEX idx_vehicle_shop_id_plate_number (shop_id, plate_number),
    INDEX idx_vehicle_shop_id_customer_id (shop_id, customer_id),
    CONSTRAINT fk_vehicle_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_vehicle_customer FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. maintenance_record — 정비 기록, 항목마다 1행 (07 3.7). 수정·삭제 없음 → updated_at 없음
CREATE TABLE maintenance_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id         BIGINT       NOT NULL,
    vehicle_id      BIGINT       NOT NULL,
    service_item_id BIGINT       NOT NULL,
    visit_key       CHAR(36)     NOT NULL,
    record_date     DATE         NOT NULL,
    mileage         INT          NULL,
    record_type     VARCHAR(20)  NOT NULL,
    source          VARCHAR(20)  NOT NULL,
    staff_id        BIGINT       NOT NULL,
    memo            VARCHAR(200) NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_maintenance_record_mileage CHECK (mileage >= 0),
    CONSTRAINT ck_maintenance_record_record_type CHECK (record_type IN ('REPLACE', 'INSPECT', 'EXTERNAL', 'DECLINE')),
    CONSTRAINT ck_maintenance_record_source CHECK (source IN ('SHOP', 'CUSTOMER_STATEMENT')),
    CONSTRAINT ck_maintenance_record_record_type_source CHECK ((record_type = 'EXTERNAL') = (source = 'CUSTOMER_STATEMENT')),
    -- 07 3.7 (shop_id, vehicle_id, service_item_id, record_date). 이름 64자 한도로 단축 (파일 머리 주석)
    INDEX idx_maintenance_record_shop_vehicle_item_date (shop_id, vehicle_id, service_item_id, record_date),
    INDEX idx_maintenance_record_shop_id_service_item_id (shop_id, service_item_id),
    INDEX idx_maintenance_record_shop_id_visit_key (shop_id, visit_key),
    INDEX idx_maintenance_record_staff_id (staff_id),
    CONSTRAINT fk_maintenance_record_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_maintenance_record_vehicle FOREIGN KEY (shop_id, vehicle_id) REFERENCES vehicle (shop_id, id),
    CONSTRAINT fk_maintenance_record_service_item FOREIGN KEY (shop_id, service_item_id) REFERENCES service_item (shop_id, id),
    CONSTRAINT fk_maintenance_record_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8. reservation — 예약 (07 3.8). 정비 기록과 잇는 칼럼 없음 (관문 1 결정 C)
--    ck_reservation_status 는 V2 가 DROP CHECK 후 'NO_SHOW' 를 더해 다시 만든다 (이름 고정)
CREATE TABLE reservation (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id             BIGINT       NOT NULL,
    vehicle_id          BIGINT       NOT NULL,
    reserved_at         DATETIME     NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    purpose_other       BOOLEAN      NOT NULL DEFAULT FALSE,
    purpose_memo        VARCHAR(200) NULL,
    created_by_staff_id BIGINT       NOT NULL,
    version             INT          NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_shop_id_id UNIQUE (shop_id, id),
    CONSTRAINT ck_reservation_reserved_at CHECK (MINUTE(reserved_at) IN (0, 30) AND SECOND(reserved_at) = 0),
    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED', 'COMPLETED', 'CANCELED')),
    INDEX idx_reservation_shop_id_reserved_at (shop_id, reserved_at),
    INDEX idx_reservation_shop_id_vehicle_id_status_reserved_at (shop_id, vehicle_id, status, reserved_at),
    INDEX idx_reservation_created_by_staff_id (created_by_staff_id),
    CONSTRAINT fk_reservation_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_reservation_vehicle FOREIGN KEY (shop_id, vehicle_id) REFERENCES vehicle (shop_id, id),
    CONSTRAINT fk_reservation_staff FOREIGN KEY (created_by_staff_id) REFERENCES staff (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9. reservation_item — 예약 목적으로 고른 항목 (07 3.9)
CREATE TABLE reservation_item (
    id              BIGINT   NOT NULL AUTO_INCREMENT,
    shop_id         BIGINT   NOT NULL,
    reservation_id  BIGINT   NOT NULL,
    service_item_id BIGINT   NOT NULL,
    created_at      DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_item_reservation_id_service_item_id UNIQUE (reservation_id, service_item_id),
    INDEX idx_reservation_item_shop_id_reservation_id (shop_id, reservation_id),
    INDEX idx_reservation_item_shop_id_service_item_id (shop_id, service_item_id),
    CONSTRAINT fk_reservation_item_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_reservation_item_reservation FOREIGN KEY (shop_id, reservation_id) REFERENCES reservation (shop_id, id),
    CONSTRAINT fk_reservation_item_service_item FOREIGN KEY (shop_id, service_item_id) REFERENCES service_item (shop_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
