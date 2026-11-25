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
