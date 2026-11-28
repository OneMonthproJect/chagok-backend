package com.wanted.chagok.domain.customer.repository;

import com.wanted.chagok.domain.customer.entity.Customer;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 모든 조회에 {@code shopId}가 들어간다 (08 0.1, 07 I-3). */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByIdAndShopId(Long id, long shopId);

    Page<Customer> findByShopId(long shopId, Pageable pageable);

    /**
     * 고객명·연락처·차량번호 일부 검색 (08 05). {@code pattern}은 서비스가 만든 {@code %…%}
     * (공백·하이픈을 뺀 검색어, LIKE 특수문자는 {@code !}로 이스케이프). 저장값 쪽도 공백·하이픈을 빼고 비교한다.
     */
    // LIMIT: 부분 일치(앞뒤 %)와 저장값 replace 때문에 이름·연락처·차량번호 인덱스로 후보를 좁히지 못하고, 그 정비소 고객을
    // 모두 읽어 비교한다(shop_id로 시작하는 인덱스로 정비소 범위만 좁힌다고 예상, 실행계획 미측정). 결과가 한 쪽을 넘으면
    // 쪽 나누기의 count 쿼리가 같은 범위를 한 번 더 읽는다. 가정(문서 근거 없음): 정비소당 고객 수백 명 단위. 실행계획·응답
    // 시간을 측정해 느린 것이 확인되면 결정 이슈로 정한다 — 후보는 정규화 칼럼(Flyway, DB 담당 L3), n-gram 전문 검색(토큰
    // 방식이라 1글자 검색 등이 LIKE와 결과가 다를 수 있어 확인 필요). 접두 일치로 바꾸면 08 05의 부분 일치 의미가 바뀐다.
    @Query("""
        select c from Customer c
        where c.shopId = :shopId
          and (replace(replace(c.name, ' ', ''), '-', '') like :pattern escape '!'
            or replace(c.phone, '-', '') like :pattern escape '!'
            or exists (select 1 from Vehicle v
                       where v.shopId = c.shopId and v.customerId = c.id
                         and replace(replace(v.plateNumber, ' ', ''), '-', '') like :pattern escape '!'))
        """)
    Page<Customer> search(@Param("shopId") long shopId, @Param("pattern") String pattern, Pageable pageable);
}
