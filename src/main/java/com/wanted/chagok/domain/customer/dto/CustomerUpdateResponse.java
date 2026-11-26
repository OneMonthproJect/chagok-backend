package com.wanted.chagok.domain.customer.dto;

import com.wanted.chagok.domain.customer.entity.Customer;

/** API 22 응답 (08 22): 바뀐 뒤의 값과 바뀐 {@code version}. */
public record CustomerUpdateResponse(long customerId, String name, String phone, boolean consent, int version) {

    public static CustomerUpdateResponse of(Customer customer) {
        return new CustomerUpdateResponse(customer.getId(), customer.getName(), customer.getPhone(), customer.isConsent(),
            customer.getVersion());
    }
}
