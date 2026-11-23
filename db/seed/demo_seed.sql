-- 차곡 시연 시드 (시연 전용 DB 전용)
--
-- 직접 실행하지 않는다. db/seed/run-demo-seed.sh 로만 실행한다 (DB 이름 _demo, Flyway 적용, shop 비어 있음 확인).
-- 원본: chagok-docs docs/07_data_ERD_DB설계서.md 7장 (7.1 정비소·계정, 7.2 A 고객·차량, 7.3 A 예약, 7.4 B, 7.5 기대값)
-- 구조: V1__init.sql (chagok-backend#5). 이 파일은 테이블·칼럼을 만들지 않는다.
-- 규칙: db/seed/README.md
--   - DELETE·TRUNCATE·DROP·ALTER·CREATE·UPDATE 문장 없음. 다시 넣으려면 시연 DB를 새로 만든다
--   - 날짜는 모두 오늘(CURDATE()) 기준 상대값. created_at·updated_at 은 DB 기본값이 없으므로 모든 행에 직접 넣는다
--   - 시연 계정 비밀번호는 BCrypt 해시로만 넣는다 (평문 없음). password_change_required = FALSE
--   - 상태(미확인/여유/임박/도래)는 저장하지 않는다. 기록 날짜로 결과가 나온다
--   - V1 기준이다. 점검 결과("주의")·진행중 예약은 V2 이후 시드에서 더한다 (07 7.2·7.3)
--
-- id 순서: 빈 DB에 이 순서로 넣으면 08 JSON 예시의 id와 같다
--   shop 1 차곡카센터(A) · 2 바른카센터(B) / staff 1 owner_a · 2 mech_a · 3 owner_b
--   service_item A 1~6 · B 7~12 (기본 기준표 순서) / customer A 1~10 · B 11 / vehicle A 1~11 · B 12
--   reservation 1 78라0004 · 2 22사0007 · 3 34나0002 · 4 55차0010
--
-- 07에 값이 없어 시드에서 고른 것 (💡)
--   - 정비 기록의 담당 정비사(staff_id): A 정비소 기록은 mech_a, B 정비소 기록은 owner_b
--   - 예약의 등록자(created_by_staff_id): 담당(assignee)과 같은 계정 (P0-핵심은 서버가 담당 = 등록자로 채움, 07 3.8)
--   - 정비 기록 주행거리: 07 7.2에 적힌 2건(12가0001 40,000 · 78라0004 45,000)만 넣고 나머지는 NULL
--   - 생성 시각: 정비소·계정·기준표 D-38개월, 고객·차량 D-37개월, 기록은 그 날짜 11:00, 예약은 예약일 3일 전 09:00
--
-- 기대 상태 (A 정비소, 날짜 기준, 07 7.2) — 대상 목록은 동의(Y) + 도래·임박
--   12가0001 김민수   엔진오일 도래 (점검은 기준이 아님, T12)           대상 ○
--   34나0002 이영희   엔진오일 도래, D+2일 10:00 예약                   대상 ○ 예약 있음
--   56다0003 박철수   브레이크패드 임박 (다음 정비일 D+20일)             대상 ○
--   78라0004 최지은   엔진오일 여유 (시나리오 B: 주행거리 55,000 → 도래)  대상 ×
--   90마0005 정우성   전부 미확인                                      대상 ×
--   11바0006 한가람   엔진오일 도래 (거절은 기준이 아님)                 대상 ○
--   22사0007 서민준   에어컨필터 여유 (외부 기록으로 재시작)             대상 ×
--   33아0008 윤하늘   타이어 임박 (점검은 기준이 아님)                   대상 ○
--   44자0009 장미래   엔진오일 도래, 동의 N                             대상 × (미동의)
--   55차0010 오세훈   배터리 임박, 방문 3회                              대상 ○
--   66카0011 오세훈   전부 미확인 (한 고객 여러 차량)                    대상 ×
-- 7.5 기대값: 오늘 예약 2건, 오늘 연락할 고객 5명, 재점검 대상 6대, A 고객 10명·차량 11대

SET @today = CURDATE();
SET @created_shop = TIMESTAMP(@today - INTERVAL 38 MONTH, '09:00:00');
SET @created_customer = TIMESTAMP(@today - INTERVAL 37 MONTH, '09:00:00');
-- 비밀번호 1234 의 BCrypt 해시 (Spring BCryptPasswordEncoder, strength 10)
SET @pw = '$2a$10$QUr5kv6hsMYP9ugLFDe1C.KnrrOLArpOIbx/62hp2NnvvUg73AE8m';

-- ---------------------------------------------------------------------
-- 7.1 서비스 관리자 · 정비소 · 계정
-- ---------------------------------------------------------------------
INSERT INTO platform_admin (login_id, password_hash, name, created_at)
VALUES ('admin', @pw, '운영관리자', @created_shop);

-- 시드 정비소는 created_by_admin_id 가 NULL (07 3.2)
INSERT INTO shop (name, phone, status, created_by_admin_id, created_at)
VALUES ('차곡카센터', '02-0000-0001', 'ACTIVE', NULL, @created_shop);
SET @shop_a = LAST_INSERT_ID();
INSERT INTO shop (name, phone, status, created_by_admin_id, created_at)
VALUES ('바른카센터', '02-0000-0002', 'ACTIVE', NULL, @created_shop);
SET @shop_b = LAST_INSERT_ID();

INSERT INTO staff (shop_id, login_id, password_hash, name, role, password_change_required, created_at)
VALUES (@shop_a, 'owner_a', @pw, '박사장', 'OWNER', FALSE, @created_shop);
SET @owner_a = LAST_INSERT_ID();
INSERT INTO staff (shop_id, login_id, password_hash, name, role, password_change_required, created_at)
VALUES (@shop_a, 'mech_a', @pw, '김정비', 'MECHANIC', FALSE, @created_shop);
SET @mech_a = LAST_INSERT_ID();
INSERT INTO staff (shop_id, login_id, password_hash, name, role, password_change_required, created_at)
VALUES (@shop_b, 'owner_b', @pw, '최사장', 'OWNER', FALSE, @created_shop);
SET @owner_b = LAST_INSERT_ID();

-- ---------------------------------------------------------------------
-- 기본 기준표 (07 3.6, D-01) — 정비소마다 6행 (07 6.1: 시드가 정비소마다 넣는다)
-- ---------------------------------------------------------------------
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_a, 'ENGINE_OIL', '엔진오일', 6, 10000, TRUE, 1, @created_shop);
SET @a_engine_oil = LAST_INSERT_ID();
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_a, 'AIR_CLEANER', '에어클리너', 12, 15000, TRUE, 2, @created_shop);
SET @a_air_cleaner = LAST_INSERT_ID();
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_a, 'AC_FILTER', '에어컨필터', 6, 10000, TRUE, 3, @created_shop);
SET @a_ac_filter = LAST_INSERT_ID();
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_a, 'BRAKE_PAD', '브레이크패드', 12, 20000, TRUE, 4, @created_shop);
SET @a_brake_pad = LAST_INSERT_ID();
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_a, 'TIRE', '타이어 상태 점검', 6, 10000, TRUE, 5, @created_shop);
SET @a_tire = LAST_INSERT_ID();
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_a, 'BATTERY', '배터리', 36, 60000, TRUE, 6, @created_shop);
SET @a_battery = LAST_INSERT_ID();

INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_b, 'ENGINE_OIL', '엔진오일', 6, 10000, TRUE, 1, @created_shop);
SET @b_engine_oil = LAST_INSERT_ID();
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_b, 'AIR_CLEANER', '에어클리너', 12, 15000, TRUE, 2, @created_shop);
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_b, 'AC_FILTER', '에어컨필터', 6, 10000, TRUE, 3, @created_shop);
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_b, 'BRAKE_PAD', '브레이크패드', 12, 20000, TRUE, 4, @created_shop);
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_b, 'TIRE', '타이어 상태 점검', 6, 10000, TRUE, 5, @created_shop);
INSERT INTO service_item (shop_id, code, name, cycle_months, cycle_km, active, sort_order, created_at)
VALUES (@shop_b, 'BATTERY', '배터리', 36, 60000, TRUE, 6, @created_shop);

-- ---------------------------------------------------------------------
-- 7.2 A 정비소 고객 10명 (전화번호는 가짜임이 드러나는 값, 장미래만 동의 N)
-- ---------------------------------------------------------------------
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '김민수', '010-0000-0001', TRUE, @created_customer, @created_customer);
SET @c_kim = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '이영희', '010-0000-0002', TRUE, @created_customer, @created_customer);
SET @c_lee = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '박철수', '010-0000-0003', TRUE, @created_customer, @created_customer);
SET @c_park = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '최지은', '010-0000-0004', TRUE, @created_customer, @created_customer);
SET @c_choi = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '정우성', '010-0000-0005', TRUE, @created_customer, @created_customer);
SET @c_jung = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '한가람', '010-0000-0006', TRUE, @created_customer, @created_customer);
SET @c_han = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '서민준', '010-0000-0007', TRUE, @created_customer, @created_customer);
SET @c_seo = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '윤하늘', '010-0000-0008', TRUE, @created_customer, @created_customer);
SET @c_yoon = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '장미래', '010-0000-0009', FALSE, @created_customer, @created_customer);
SET @c_jang = LAST_INSERT_ID();
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_a, '오세훈', '010-0000-0010', TRUE, @created_customer, @created_customer);
SET @c_oh = LAST_INSERT_ID();

-- ---------------------------------------------------------------------
-- 7.2 A 정비소 차량 11대 (차종은 모두 값 있음 D-58, 연식·최근 주행거리는 07 7.2 표)
-- ---------------------------------------------------------------------
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_kim, '12가0001', '아반떼', 2019, 40000, @created_customer, @created_customer);
SET @v_12ga = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_lee, '34나0002', '소나타', 2018, 60000, @created_customer, @created_customer);
SET @v_34na = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_park, '56다0003', '그랜저', 2017, 80000, @created_customer, @created_customer);
SET @v_56da = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_choi, '78라0004', 'K5', 2020, 45000, @created_customer, @created_customer);
SET @v_78ra = LAST_INSERT_ID();
-- 주행거리 없음 → "정비 시작 때 입력" 확인용
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_jung, '90마0005', '아반떼', 2019, NULL, @created_customer, @created_customer);
SET @v_90ma = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_han, '11바0006', '투싼', 2016, 70000, @created_customer, @created_customer);
SET @v_11ba = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_seo, '22사0007', '쏘렌토', 2018, 55000, @created_customer, @created_customer);
SET @v_22sa = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_yoon, '33아0008', '스포티지', 2021, 30000, @created_customer, @created_customer);
SET @v_33a = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_jang, '44자0009', '아반떼', 2015, 90000, @created_customer, @created_customer);
SET @v_44ja = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_oh, '55차0010', '쏘나타', 2018, 97000, @created_customer, @created_customer);
SET @v_55cha = LAST_INSERT_ID();
-- 한 고객 여러 차량 + 연식·주행거리 없음 확인용
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_a, @c_oh, '66카0011', 'K3', NULL, NULL, @created_customer, @created_customer);
SET @v_66ka = LAST_INSERT_ID();

-- ---------------------------------------------------------------------
-- 7.4 B 정비소 — A와 같은 차량번호 12가0001 (격리 확인 X1·X2·X7)
-- ---------------------------------------------------------------------
INSERT INTO customer (shop_id, name, phone, consent, created_at, updated_at)
VALUES (@shop_b, '강동원', '010-0000-0101', TRUE, @created_customer, @created_customer);
SET @c_kang = LAST_INSERT_ID();
INSERT INTO vehicle (shop_id, customer_id, plate_number, model, model_year, latest_mileage, created_at, updated_at)
VALUES (@shop_b, @c_kang, '12가0001', '아반떼', NULL, NULL, @created_customer, @created_customer);
SET @v_b_12ga = LAST_INSERT_ID();

-- ---------------------------------------------------------------------
-- 정비 기록 (07 7.2·7.4). 항목마다 1행, 같은 방문은 같은 visit_key. 외부(EXTERNAL)만 출처가 고객 진술
-- ---------------------------------------------------------------------
-- 12가0001: 엔진오일 교체 D-6M-7d (40,000km), 엔진오일 점검 D-2M → 엔진오일 도래
SET @d = @today - INTERVAL 6 MONTH - INTERVAL 7 DAY;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_12ga, @a_engine_oil, '5eed0000-0000-4000-8000-000000000001', @d, 40000, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));
SET @d = @today - INTERVAL 2 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_12ga, @a_engine_oil, '5eed0000-0000-4000-8000-000000000002', @d, NULL, 'INSPECT', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 34나0002: 엔진오일 교체 D-6M-10d → 엔진오일 도래 (D+2일 예약 있음)
SET @d = @today - INTERVAL 6 MONTH - INTERVAL 10 DAY;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_34na, @a_engine_oil, '5eed0000-0000-4000-8000-000000000003', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 56다0003: 브레이크패드 교체 D-12M+20d → 브레이크패드 임박 (다음 정비일 D+20일)
SET @d = @today - INTERVAL 12 MONTH + INTERVAL 20 DAY;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_56da, @a_brake_pad, '5eed0000-0000-4000-8000-000000000004', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 78라0004: 엔진오일 교체 D-2M (45,000km) → 엔진오일 여유. 시나리오 B: 방문 주행거리 55,000 입력 → 도래 (T8)
SET @d = @today - INTERVAL 2 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_78ra, @a_engine_oil, '5eed0000-0000-4000-8000-000000000005', @d, 45000, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 90마0005: 기록 없음 → 전부 미확인 (T1)

-- 11바0006: 엔진오일 교체 D-8M, 엔진오일 거절 D-1M → 엔진오일 도래 (거절은 주기를 다시 시작하지 않음)
SET @d = @today - INTERVAL 8 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_11ba, @a_engine_oil, '5eed0000-0000-4000-8000-000000000006', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));
SET @d = @today - INTERVAL 1 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_11ba, @a_engine_oil, '5eed0000-0000-4000-8000-000000000007', @d, NULL, 'DECLINE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 22사0007: 에어컨필터 교체 D-13M, 에어컨필터 외부 D-1M (고객 진술) → 에어컨필터 여유 (외부로 재시작)
SET @d = @today - INTERVAL 13 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_22sa, @a_ac_filter, '5eed0000-0000-4000-8000-000000000008', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));
SET @d = @today - INTERVAL 1 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_22sa, @a_ac_filter, '5eed0000-0000-4000-8000-000000000009', @d, NULL, 'EXTERNAL', 'CUSTOMER_STATEMENT', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 33아0008: 타이어 교체 D-6M+20d, 타이어 점검 D-2M → 타이어 임박 (점검 결과 "주의"·메모는 V2 이후)
SET @d = @today - INTERVAL 6 MONTH + INTERVAL 20 DAY;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_33a, @a_tire, '5eed0000-0000-4000-8000-000000000010', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));
SET @d = @today - INTERVAL 2 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_33a, @a_tire, '5eed0000-0000-4000-8000-000000000011', @d, NULL, 'INSPECT', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 44자0009: 엔진오일 교체 D-7M → 엔진오일 도래, 동의 N이라 대상 목록에 없음
SET @d = @today - INTERVAL 7 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_44ja, @a_engine_oil, '5eed0000-0000-4000-8000-000000000012', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 55차0010: 배터리 교체 D-36M+20d, 에어클리너·에어컨필터 교체 D-3M (같은 방문), 엔진오일 교체 D-1M → 배터리 임박, 방문 3회
SET @d = @today - INTERVAL 36 MONTH + INTERVAL 20 DAY;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_55cha, @a_battery, '5eed0000-0000-4000-8000-000000000013', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));
SET @d = @today - INTERVAL 3 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_55cha, @a_air_cleaner, '5eed0000-0000-4000-8000-000000000014', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_55cha, @a_ac_filter, '5eed0000-0000-4000-8000-000000000014', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));
SET @d = @today - INTERVAL 1 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_a, @v_55cha, @a_engine_oil, '5eed0000-0000-4000-8000-000000000015', @d, NULL, 'REPLACE', 'SHOP', @mech_a, NULL, TIMESTAMP(@d, '11:00:00'));

-- 66카0011: 기록 없음 → 전부 미확인

-- B 정비소 12가0001: 엔진오일 교체 D-7M (A에서는 보이지 않아야 함)
SET @d = @today - INTERVAL 7 MONTH;
INSERT INTO maintenance_record (shop_id, vehicle_id, service_item_id, visit_key, record_date, mileage, record_type, source, staff_id, memo, created_at)
VALUES (@shop_b, @v_b_12ga, @b_engine_oil, '5eed0000-0000-4000-8000-000000000016', @d, NULL, 'REPLACE', 'SHOP', @owner_b, NULL, TIMESTAMP(@d, '11:00:00'));

-- ---------------------------------------------------------------------
-- 7.3 A 정비소 예약 4건 (30분 단위·초 00, 상태 RESERVED만 — V1). 담당 = 등록자
-- ---------------------------------------------------------------------
-- 1) 78라0004 오늘 10:00, 엔진오일, 담당 mech_a — 오늘 예약, 시나리오 B 시작점
SET @at = TIMESTAMP(@today, '10:00:00');
INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, purpose_other, purpose_memo, assignee_staff_id, created_by_staff_id, version, created_at, updated_at)
VALUES (@shop_a, @v_78ra, @at, 'RESERVED', FALSE, NULL, @mech_a, @mech_a, 0,
        TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'), TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'));
SET @r1 = LAST_INSERT_ID();
INSERT INTO reservation_item (shop_id, reservation_id, service_item_id, created_at)
VALUES (@shop_a, @r1, @a_engine_oil, TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'));

-- 2) 22사0007 오늘 14:30, 기타 + 메모 "에어컨 냄새", 담당 owner_a — 오늘 예약
SET @at = TIMESTAMP(@today, '14:30:00');
INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, purpose_other, purpose_memo, assignee_staff_id, created_by_staff_id, version, created_at, updated_at)
VALUES (@shop_a, @v_22sa, @at, 'RESERVED', TRUE, '에어컨 냄새', @owner_a, @owner_a, 0,
        TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'), TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'));

-- 3) 34나0002 D+2일 10:00, 엔진오일, 담당 mech_a — 대상 목록 "예약 있음"
SET @at = TIMESTAMP(@today + INTERVAL 2 DAY, '10:00:00');
INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, purpose_other, purpose_memo, assignee_staff_id, created_by_staff_id, version, created_at, updated_at)
VALUES (@shop_a, @v_34na, @at, 'RESERVED', FALSE, NULL, @mech_a, @mech_a, 0,
        TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'), TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'));
SET @r3 = LAST_INSERT_ID();
INSERT INTO reservation_item (shop_id, reservation_id, service_item_id, created_at)
VALUES (@shop_a, @r3, @a_engine_oil, TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'));

-- 4) 55차0010 어제 15:00, 배터리, 담당 owner_a — 처리 필요 (D-23), "예약 있음"으로 세지 않음
SET @at = TIMESTAMP(@today - INTERVAL 1 DAY, '15:00:00');
INSERT INTO reservation (shop_id, vehicle_id, reserved_at, status, purpose_other, purpose_memo, assignee_staff_id, created_by_staff_id, version, created_at, updated_at)
VALUES (@shop_a, @v_55cha, @at, 'RESERVED', FALSE, NULL, @owner_a, @owner_a, 0,
        TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'), TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'));
SET @r4 = LAST_INSERT_ID();
INSERT INTO reservation_item (shop_id, reservation_id, service_item_id, created_at)
VALUES (@shop_a, @r4, @a_battery, TIMESTAMP(DATE(@at) - INTERVAL 3 DAY, '09:00:00'));
